package com.jayaprakash.oms.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jayaprakash.oms.common.events.OrderCancelledEvent;
import com.jayaprakash.oms.common.events.OrderCreatedEvent;
import com.jayaprakash.oms.order.api.dto.OrderLineItemRequest;
import com.jayaprakash.oms.order.api.dto.OrderResponse;
import com.jayaprakash.oms.order.api.dto.PlaceOrderRequest;
import com.jayaprakash.oms.order.domain.Order;
import com.jayaprakash.oms.order.domain.OrderLineItem;
import com.jayaprakash.oms.order.domain.OrderStatus;
import com.jayaprakash.oms.order.messaging.OrderEventPublisher;
import com.jayaprakash.oms.order.repository.OrderRepository;
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
 * Unit tests for {@link OrderService}: the saga's state machine.
 *
 * <p>No Kafka, no database, no Spring context — the repository and publisher are
 * mocked, so these tests describe policy and run in milliseconds. They are the
 * first place to look when an order ends up in the wrong state.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderEventPublisher eventPublisher;

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderRepository, eventPublisher);
    }

    /**
     * Builds a saved-looking order in a chosen state.
     *
     * <p>Input: the id to pretend it was assigned and the status it should be
     * in. Processing: constructs the entity and forces both fields, since JPA
     * normally assigns the id and there is no setter by design. Output: an
     * {@link Order} suitable for stubbing {@code findById}.
     *
     * @param id     the id to assign
     * @param status the status to put it in
     * @return a ready-to-use order
     */
    private Order existingOrder(Long id, OrderStatus status) {
        Order order = new Order("ORD-TEST", "cust-1",
                List.of(new OrderLineItem("LAPTOP-001", 2, new BigDecimal("500.00"))));
        ReflectionTestUtils.setField(order, "id", id);
        order.changeStatus(status);
        return order;
    }

    @Test
    @DisplayName("placing an order saves it as PENDING and totals the line items")
    void placeOrderSavesPendingAndTotals() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 42L);
            return order;
        });

        OrderResponse response = orderService.placeOrder(new PlaceOrderRequest("cust-1", List.of(
                new OrderLineItemRequest("LAPTOP-001", 2, new BigDecimal("500.00")),
                new OrderLineItemRequest("PHONE-002", 1, new BigDecimal("250.50")))));

        assertThat(response.status()).isEqualTo("PENDING");
        // 2 * 500.00 + 1 * 250.50
        assertThat(response.totalAmount()).isEqualByComparingTo("1250.50");
        assertThat(response.orderNumber()).startsWith("ORD-");
    }

    @Test
    @DisplayName("placing an order starts the saga by publishing OrderCreatedEvent")
    void placeOrderPublishesCreatedEvent() {
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 42L);
            return order;
        });

        orderService.placeOrder(new PlaceOrderRequest("cust-1", List.of(
                new OrderLineItemRequest("LAPTOP-001", 2, new BigDecimal("500.00")))));

        ArgumentCaptor<OrderCreatedEvent> captor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(eventPublisher).publishOrderCreated(captor.capture());

        OrderCreatedEvent event = captor.getValue();
        assertThat(event.orderId()).isEqualTo(42L);
        assertThat(event.customerId()).isEqualTo("cust-1");
        assertThat(event.items()).hasSize(1);
        assertThat(event.totalAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("a reservation confirmation moves PENDING to INVENTORY_RESERVED")
    void inventoryReservedAdvancesStatus() {
        Order order = existingOrder(1L, OrderStatus.PENDING);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.markInventoryReserved(1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        verify(orderRepository).save(order);
    }

    @Test
    @DisplayName("a redelivered reservation confirmation does not rewind a confirmed order")
    void inventoryReservedIsIdempotent() {
        Order order = existingOrder(1L, OrderStatus.CONFIRMED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.markInventoryReserved(1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("a successful payment confirms the order")
    void paymentCompletedConfirmsOrder() {
        Order order = existingOrder(1L, OrderStatus.INVENTORY_RESERVED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.confirmOrder(1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("a redelivered payment confirmation cannot revive a cancelled order")
    void confirmIsIdempotentAgainstCancelled() {
        Order order = existingOrder(1L, OrderStatus.CANCELLED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.confirmOrder(1L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("an inventory failure cancels the order WITHOUT publishing compensation")
    void inventoryFailureNeedsNoCompensation() {
        Order order = existingOrder(1L, OrderStatus.PENDING);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.cancelForInventoryFailure(1L, "Insufficient stock for RARE-003");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getFailureReason()).contains("RARE-003");
        // Nothing was reserved and nothing was charged, so there is nothing to undo.
        verify(eventPublisher, never()).publishOrderCancelled(any());
    }

    @Test
    @DisplayName("a payment failure cancels the order AND publishes the compensating event")
    void paymentFailureTriggersCompensation() {
        Order order = existingOrder(1L, OrderStatus.INVENTORY_RESERVED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.cancelForPaymentFailure(1L, "Card declined");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getFailureReason()).isEqualTo("Card declined");

        // This is the saga unwinding: inventory must get its stock back.
        ArgumentCaptor<OrderCancelledEvent> captor = ArgumentCaptor.forClass(OrderCancelledEvent.class);
        verify(eventPublisher).publishOrderCancelled(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(1L);
        assertThat(captor.getValue().reason()).isEqualTo("Card declined");
    }

    @Test
    @DisplayName("a duplicate payment failure does not publish compensation twice")
    void compensationIsPublishedOnlyOnce() {
        Order order = existingOrder(1L, OrderStatus.CANCELLED);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.cancelForPaymentFailure(1L, "Card declined");

        // Releasing the same stock twice would silently inflate inventory.
        verify(eventPublisher, never()).publishOrderCancelled(any());
    }

    @Test
    @DisplayName("an event for an unknown order fails loudly instead of being swallowed")
    void unknownOrderThrows() {
        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.confirmOrder(999L))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("999");
    }
}
