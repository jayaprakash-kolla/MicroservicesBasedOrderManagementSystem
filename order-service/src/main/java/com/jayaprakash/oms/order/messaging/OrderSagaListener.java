package com.jayaprakash.oms.order.messaging;

import com.jayaprakash.oms.common.events.InventoryFailedEvent;
import com.jayaprakash.oms.common.events.InventoryReservedEvent;
import com.jayaprakash.oms.common.events.PaymentCompletedEvent;
import com.jayaprakash.oms.common.events.PaymentFailedEvent;
import com.jayaprakash.oms.common.events.Topics;
import com.jayaprakash.oms.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Listens for the outcome of every downstream saga step.
 *
 * <p>Reading these four methods top to bottom tells you the entire saga from
 * order-service's point of view. Each one is a thin adapter: it unwraps the
 * event and delegates, so the decision logic stays in {@code OrderService}
 * where it can be unit tested without Kafka.
 *
 * <p>Any exception thrown here is handled by the shared error handler: two
 * retries a second apart, then the dead-letter topic.
 */
@Component
public class OrderSagaListener {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaListener.class);

    private final OrderService orderService;

    /**
     * @param orderService where the actual state transitions happen
     */
    public OrderSagaListener(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Saga step 2 succeeded: stock is held.
     *
     * <p>Input: an {@link InventoryReservedEvent}. Processing: advances the
     * order to INVENTORY_RESERVED. Output: none. Payment-service is listening
     * to the same event and will charge the customer in parallel.
     *
     * @param event the reservation confirmation
     */
    @KafkaListener(topics = Topics.INVENTORY_RESERVED, groupId = "order-service")
    public void onInventoryReserved(InventoryReservedEvent event) {
        log.info("Received InventoryReservedEvent: orderId={}", event.orderId());
        orderService.markInventoryReserved(event.orderId());
    }

    /**
     * Saga step 2 failed: not enough stock.
     *
     * <p>Input: an {@link InventoryFailedEvent}. Processing: cancels the order.
     * Output: none. No compensation is published because nothing was reserved.
     *
     * @param event the failure notice
     */
    @KafkaListener(topics = Topics.INVENTORY_FAILED, groupId = "order-service")
    public void onInventoryFailed(InventoryFailedEvent event) {
        log.info("Received InventoryFailedEvent: orderId={} reason={}", event.orderId(), event.reason());
        orderService.cancelForInventoryFailure(event.orderId(), event.reason());
    }

    /**
     * Saga step 3 succeeded: the customer paid.
     *
     * <p>Input: a {@link PaymentCompletedEvent}. Processing: confirms the order,
     * completing the saga. Output: none.
     *
     * @param event the payment confirmation
     */
    @KafkaListener(topics = Topics.PAYMENT_COMPLETED, groupId = "order-service")
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received PaymentCompletedEvent: orderId={} paymentId={}",
                event.orderId(), event.paymentId());
        orderService.confirmOrder(event.orderId());
    }

    /**
     * Saga step 3 failed: the charge was declined.
     *
     * <p>Input: a {@link PaymentFailedEvent}. Processing: cancels the order and
     * publishes the compensating {@code OrderCancelledEvent} so inventory is
     * released. Output: none.
     *
     * <p>This is the branch worth stepping through in a debugger — it is where
     * the saga unwinds work that has already been committed in another service.
     *
     * @param event the decline notice
     */
    @KafkaListener(topics = Topics.PAYMENT_FAILED, groupId = "order-service")
    public void onPaymentFailed(PaymentFailedEvent event) {
        log.info("Received PaymentFailedEvent: orderId={} reason={}", event.orderId(), event.reason());
        orderService.cancelForPaymentFailure(event.orderId(), event.reason());
    }
}
