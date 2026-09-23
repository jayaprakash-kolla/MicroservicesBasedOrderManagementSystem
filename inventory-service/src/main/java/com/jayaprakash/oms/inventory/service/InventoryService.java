package com.jayaprakash.oms.inventory.service;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga step 2: hold stock for new orders, and give it back when they fail.
 *
 * <p>The all-or-nothing rule matters here. An order for three SKUs where only
 * two are in stock must reserve nothing at all — a partial reservation would
 * leave stock held for an order that can never be fulfilled.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryItemRepository itemRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryEventPublisher eventPublisher;

    /**
     * @param itemRepository        stock levels
     * @param reservationRepository the record of what is held for whom
     * @param eventPublisher        publishes the outcome back to the saga
     */
    public InventoryService(InventoryItemRepository itemRepository,
                            ReservationRepository reservationRepository,
                            InventoryEventPublisher eventPublisher) {
        this.itemRepository = itemRepository;
        this.reservationRepository = reservationRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Attempts to reserve everything a new order needs.
     *
     * <p>Input: the {@link OrderCreatedEvent}. Processing: checks availability
     * for every line first, and only then mutates anything. If any line cannot
     * be satisfied it publishes {@link InventoryFailedEvent} and changes
     * nothing; otherwise it reserves each line, records the holds, and publishes
     * {@link InventoryReservedEvent}. Output: none directly — the result is an
     * event on Kafka.
     *
     * <p>The whole method runs in one transaction, so a failure partway through
     * rolls the stock changes back rather than leaving them half applied.
     *
     * @param event the newly created order
     */
    @Transactional
    public void reserveForOrder(OrderCreatedEvent event) {

        // Idempotency: Kafka delivers at least once, and re-reserving stock for
        // an order we already handled would quietly lose inventory.
        if (!reservationRepository.findByOrderId(event.orderId()).isEmpty()) {
            log.warn("Order {} already has reservations, ignoring duplicate event", event.orderId());
            return;
        }

        // Phase 1: check everything before changing anything.
        Optional<String> problem = findUnsatisfiableLine(event.items());
        if (problem.isPresent()) {
            log.info("Reservation refused for order {}: {}", event.orderId(), problem.get());
            eventPublisher.publishInventoryFailed(
                    new InventoryFailedEvent(event.orderId(), problem.get()));
            return;
        }

        // Phase 2: nothing can fail now, so apply the changes.
        List<Reservation> holds = new ArrayList<>();
        for (OrderLineItemDto line : event.items()) {
            InventoryItem item = itemRepository.findById(line.skuCode()).orElseThrow();
            item.reserve(line.quantity());
            itemRepository.save(item);
            holds.add(new Reservation(event.orderId(), line.skuCode(), line.quantity()));
        }
        reservationRepository.saveAll(holds);

        log.info("Reserved {} line(s) for order {}", holds.size(), event.orderId());

        // The reservation id of the first hold is enough for correlation; the
        // full detail stays in this service, which is the point of the design.
        eventPublisher.publishInventoryReserved(new InventoryReservedEvent(
                event.orderId(),
                holds.get(0).getId(),
                event.customerId(),
                event.totalAmount()));
    }

    /**
     * Compensating action: gives back everything held for a cancelled order.
     *
     * <p>Input: the order id and why it was cancelled. Processing: finds every
     * hold still in RESERVED state, moves the units back to available, and marks
     * each hold RELEASED. Output: none.
     *
     * <p>Filtering on RESERVED is what makes this safe to run twice: a
     * redelivered cancellation finds nothing left to release and does nothing.
     *
     * @param orderId the cancelled order
     * @param reason  why it was cancelled, for the log
     */
    @Transactional
    public void releaseForOrder(Long orderId, String reason) {

        List<Reservation> active =
                reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.RESERVED);

        if (active.isEmpty()) {
            log.warn("Nothing to release for order {} (already released or never reserved)", orderId);
            return;
        }

        for (Reservation hold : active) {
            InventoryItem item = itemRepository.findById(hold.getSkuCode()).orElseThrow();
            item.release(hold.getQuantity());
            itemRepository.save(item);
            hold.markReleased();
        }
        reservationRepository.saveAll(active);

        log.info("Released {} hold(s) for cancelled order {}: {}", active.size(), orderId, reason);
    }

    /**
     * Finds the first order line that cannot be satisfied.
     *
     * <p>Input: the order's line items. Processing: for each line, checks the
     * SKU exists and has enough available stock. Output: a description of the
     * first problem found, or empty when every line is satisfiable.
     *
     * <p>Returning early on the first problem is intentional — the caller only
     * needs to know that the order cannot proceed, and one clear reason is more
     * useful in an error message than a list.
     *
     * @param lines the requested lines
     * @return the reason the order cannot be fulfilled, if any
     */
    private Optional<String> findUnsatisfiableLine(List<OrderLineItemDto> lines) {
        for (OrderLineItemDto line : lines) {
            Optional<InventoryItem> item = itemRepository.findById(line.skuCode());

            if (item.isEmpty()) {
                return Optional.of("Unknown SKU: " + line.skuCode());
            }
            if (!item.get().hasStockFor(line.quantity())) {
                return Optional.of("Insufficient stock for " + line.skuCode()
                        + ": requested " + line.quantity()
                        + ", available " + item.get().getAvailableQuantity());
            }
        }
        return Optional.empty();
    }

    /**
     * Lists all stock rows.
     *
     * <p>Input: none. Output: every {@link InventoryItem}, used by the REST
     * endpoint so you can watch stock move while a saga runs.
     *
     * @return all stock rows
     */
    @Transactional(readOnly = true)
    public List<InventoryItem> listStock() {
        return itemRepository.findAll();
    }

    /**
     * Adds stock for a SKU, creating the row if it does not exist.
     *
     * <p>Input: SKU code and how many units to add. Processing: increments
     * available stock. Output: the updated row.
     *
     * @param skuCode  the product
     * @param quantity units to add
     * @return the updated stock row
     */
    @Transactional
    public InventoryItem addStock(String skuCode, int quantity) {
        InventoryItem item = itemRepository.findById(skuCode)
                .orElseGet(() -> new InventoryItem(skuCode, 0));
        item.addStock(quantity);
        return itemRepository.save(item);
    }
}
