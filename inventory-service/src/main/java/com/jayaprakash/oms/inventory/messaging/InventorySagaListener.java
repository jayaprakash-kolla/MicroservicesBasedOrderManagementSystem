package com.jayaprakash.oms.inventory.messaging;

import com.jayaprakash.oms.common.events.OrderCancelledEvent;
import com.jayaprakash.oms.common.events.OrderCreatedEvent;
import com.jayaprakash.oms.common.events.PaymentCompletedEvent;
import com.jayaprakash.oms.common.events.Topics;
import com.jayaprakash.oms.inventory.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The three events inventory-service cares about.
 *
 * <p>Notice what is absent: no reference to payments, no HTTP call to
 * order-service, no shared database. That isolation is the whole benefit of the
 * event-driven approach — inventory-service can be deployed, scaled, or
 * restarted without coordinating with anyone.
 */
@Component
public class InventorySagaListener {

    private static final Logger log = LoggerFactory.getLogger(InventorySagaListener.class);

    private final InventoryService inventoryService;

    /**
     * @param inventoryService where the stock logic lives
     */
    public InventorySagaListener(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * A new order needs stock held.
     *
     * <p>Input: an {@link OrderCreatedEvent}. Processing: delegates to the
     * service, which publishes either a reserved or a failed event. Output: none.
     *
     * @param event the newly created order
     */
    @KafkaListener(topics = Topics.ORDER_CREATED, groupId = "inventory-service")
    public void onOrderCreated(OrderCreatedEvent event) {
        log.info("Received OrderCreatedEvent: orderId={} lines={}",
                event.orderId(), event.items().size());
        inventoryService.reserveForOrder(event);
    }

    /**
     * An order was cancelled after stock had been held, so give it back.
     *
     * <p>Input: an {@link OrderCancelledEvent}. Processing: releases every
     * active hold for that order. Output: none.
     *
     * <p>This listener is the receiving half of the compensating transaction —
     * the other half is order-service publishing the event after a failed
     * payment.
     *
     * @param event the cancellation notice
     */
    @KafkaListener(topics = Topics.ORDER_CANCELLED, groupId = "inventory-service")
    public void onOrderCancelled(OrderCancelledEvent event) {
        log.info("Received OrderCancelledEvent (compensation): orderId={}", event.orderId());
        inventoryService.releaseForOrder(event.orderId(), event.reason());
    }

    /**
     * The order was paid for, so the held units are sold for good.
     *
     * <p>Input: a {@link PaymentCompletedEvent}. Processing: commits every
     * active hold for that order. Output: none.
     *
     * <p>Order-service consumes the same topic in its own consumer group, so
     * both services get their own copy of the event and neither knows about
     * the other.
     *
     * @param event the successful payment
     */
    @KafkaListener(topics = Topics.PAYMENT_COMPLETED, groupId = "inventory-service")
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received PaymentCompletedEvent: orderId={}", event.orderId());
        inventoryService.commitForOrder(event.orderId());
    }
}
