package com.jayaprakash.oms.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jayaprakash.oms.common.events.InventoryFailedEvent;
import com.jayaprakash.oms.common.events.InventoryReservedEvent;
import com.jayaprakash.oms.common.events.OrderCreatedEvent;
import com.jayaprakash.oms.common.events.OrderLineItemDto;
import com.jayaprakash.oms.inventory.domain.InventoryItem;
import com.jayaprakash.oms.inventory.domain.Reservation;
import com.jayaprakash.oms.inventory.domain.ReservationStatus;
import com.jayaprakash.oms.inventory.messaging.InventoryEventPublisher;
import com.jayaprakash.oms.inventory.repository.InventoryItemRepository;
import com.jayaprakash.oms.inventory.repository.ReservationRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link InventoryService}.
 *
 * <p>The most important behaviour proved here is all-or-nothing reservation: a
 * partially reserved order would hold stock that can never be sold or released.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryItemRepository itemRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private InventoryEventPublisher eventPublisher;

    private InventoryService inventoryService;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService(itemRepository, reservationRepository, eventPublisher);
    }

    /**
     * Builds an order event.
     *
     * <p>Input: the line items to include. Output: an {@link OrderCreatedEvent}
     * for order 1, so tests only express what differs.
     *
     * @param lines the requested lines
     * @return the event to feed to the service
     */
    private OrderCreatedEvent orderEvent(OrderLineItemDto... lines) {
        return new OrderCreatedEvent(
                1L, "ORD-1", "cust-1", List.of(lines), new BigDecimal("100.00"));
    }

    /**
     * Builds a saved-looking reservation.
     *
     * <p>Input: sku and quantity. Output: a {@link Reservation} with a forced id,
     * mimicking a row that has already been persisted.
     */
    private Reservation savedReservation(String sku, int quantity) {
        Reservation reservation = new Reservation(1L, sku, quantity);
        ReflectionTestUtils.setField(reservation, "id", 10L);
        return reservation;
    }

    @Test
    @DisplayName("reserves every line and publishes InventoryReservedEvent when stock allows")
    void reservesWhenStockIsAvailable() {
        InventoryItem laptop = new InventoryItem("LAPTOP-001", 10);
        when(reservationRepository.findByOrderId(1L)).thenReturn(List.of());
        when(itemRepository.findById("LAPTOP-001")).thenReturn(Optional.of(laptop));
        when(reservationRepository.saveAll(any())).thenAnswer(invocation ->
                List.of(savedReservation("LAPTOP-001", 3)));

        inventoryService.reserveForOrder(orderEvent(
                new OrderLineItemDto("LAPTOP-001", 3, new BigDecimal("100.00"))));

        assertThat(laptop.getAvailableQuantity()).isEqualTo(7);
        assertThat(laptop.getReservedQuantity()).isEqualTo(3);

        ArgumentCaptor<InventoryReservedEvent> captor =
                ArgumentCaptor.forClass(InventoryReservedEvent.class);
        verify(eventPublisher).publishInventoryReserved(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(1L);
        // The amount is carried forward so payment-service needs no callback.
        assertThat(captor.getValue().totalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("publishes InventoryFailedEvent and changes nothing when stock is short")
    void refusesWhenStockIsInsufficient() {
        InventoryItem rare = new InventoryItem("RARE-003", 1);
        when(reservationRepository.findByOrderId(1L)).thenReturn(List.of());
        when(itemRepository.findById("RARE-003")).thenReturn(Optional.of(rare));

        inventoryService.reserveForOrder(orderEvent(
                new OrderLineItemDto("RARE-003", 5, new BigDecimal("100.00"))));

        assertThat(rare.getAvailableQuantity()).isEqualTo(1);
        assertThat(rare.getReservedQuantity()).isZero();

        ArgumentCaptor<InventoryFailedEvent> captor = ArgumentCaptor.forClass(InventoryFailedEvent.class);
        verify(eventPublisher).publishInventoryFailed(captor.capture());
        assertThat(captor.getValue().reason()).contains("Insufficient stock for RARE-003");
        verify(eventPublisher, never()).publishInventoryReserved(any());
    }

    @Test
    @DisplayName("reserves nothing at all when only some lines can be satisfied")
    void reservationIsAllOrNothing() {
        InventoryItem laptop = new InventoryItem("LAPTOP-001", 10);
        InventoryItem rare = new InventoryItem("RARE-003", 1);

        when(reservationRepository.findByOrderId(1L)).thenReturn(List.of());
        when(itemRepository.findById("LAPTOP-001")).thenReturn(Optional.of(laptop));
        when(itemRepository.findById("RARE-003")).thenReturn(Optional.of(rare));

        inventoryService.reserveForOrder(orderEvent(
                new OrderLineItemDto("LAPTOP-001", 2, new BigDecimal("100.00")),
                new OrderLineItemDto("RARE-003", 5, new BigDecimal("100.00"))));

        // The laptop line was satisfiable but must NOT have been touched, or the
        // stock would be held for an order that can never complete.
        assertThat(laptop.getAvailableQuantity()).isEqualTo(10);
        assertThat(laptop.getReservedQuantity()).isZero();
        verify(eventPublisher).publishInventoryFailed(any());
    }

    @Test
    @DisplayName("refuses an unknown SKU with a clear reason")
    void refusesUnknownSku() {
        when(reservationRepository.findByOrderId(1L)).thenReturn(List.of());
        when(itemRepository.findById("GHOST-999")).thenReturn(Optional.empty());

        inventoryService.reserveForOrder(orderEvent(
                new OrderLineItemDto("GHOST-999", 1, new BigDecimal("100.00"))));

        ArgumentCaptor<InventoryFailedEvent> captor = ArgumentCaptor.forClass(InventoryFailedEvent.class);
        verify(eventPublisher).publishInventoryFailed(captor.capture());
        assertThat(captor.getValue().reason()).contains("Unknown SKU");
    }

    @Test
    @DisplayName("ignores a redelivered order event instead of reserving twice")
    void duplicateOrderEventIsIgnored() {
        when(reservationRepository.findByOrderId(1L))
                .thenReturn(List.of(savedReservation("LAPTOP-001", 3)));

        inventoryService.reserveForOrder(orderEvent(
                new OrderLineItemDto("LAPTOP-001", 3, new BigDecimal("100.00"))));

        verify(eventPublisher, never()).publishInventoryReserved(any());
        verify(eventPublisher, never()).publishInventoryFailed(any());
        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("compensation returns the held units to available stock")
    void releaseGivesStockBack() {
        InventoryItem laptop = new InventoryItem("LAPTOP-001", 10);
        laptop.reserve(3);   // 7 available, 3 reserved

        Reservation hold = savedReservation("LAPTOP-001", 3);
        when(reservationRepository.findByOrderIdAndStatus(1L, ReservationStatus.RESERVED))
                .thenReturn(List.of(hold));
        when(itemRepository.findById("LAPTOP-001")).thenReturn(Optional.of(laptop));

        inventoryService.releaseForOrder(1L, "Card declined");

        assertThat(laptop.getAvailableQuantity()).isEqualTo(10);
        assertThat(laptop.getReservedQuantity()).isZero();
        assertThat(hold.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    @DisplayName("a duplicate cancellation does not release the same stock twice")
    void releaseIsIdempotent() {
        // Already released, so the RESERVED query comes back empty.
        when(reservationRepository.findByOrderIdAndStatus(1L, ReservationStatus.RESERVED))
                .thenReturn(List.of());

        inventoryService.releaseForOrder(1L, "Card declined");

        verify(itemRepository, never()).save(any());
    }

    @Test
    @DisplayName("a completed payment turns the hold into a permanent reduction")
    void commitClearsReservedCount() {
        InventoryItem laptop = new InventoryItem("LAPTOP-001", 10);
        laptop.reserve(3);   // 7 available, 3 reserved

        Reservation hold = savedReservation("LAPTOP-001", 3);
        when(reservationRepository.findByOrderIdAndStatus(1L, ReservationStatus.RESERVED))
                .thenReturn(List.of(hold));
        when(itemRepository.findById("LAPTOP-001")).thenReturn(Optional.of(laptop));

        inventoryService.commitForOrder(1L);

        // Sold units do not come back to available; they just stop being "held".
        assertThat(laptop.getAvailableQuantity()).isEqualTo(7);
        assertThat(laptop.getReservedQuantity()).isZero();
        assertThat(hold.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
    }

    @Test
    @DisplayName("a duplicate payment confirmation does not commit the same stock twice")
    void commitIsIdempotent() {
        // Already committed, so the RESERVED query comes back empty.
        when(reservationRepository.findByOrderIdAndStatus(1L, ReservationStatus.RESERVED))
                .thenReturn(List.of());

        inventoryService.commitForOrder(1L);

        verify(itemRepository, never()).save(any());
    }
}
