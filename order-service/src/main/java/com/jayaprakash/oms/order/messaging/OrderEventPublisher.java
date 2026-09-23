package com.jayaprakash.oms.order.messaging;

import com.jayaprakash.oms.common.events.OrderCancelledEvent;
import com.jayaprakash.oms.common.events.OrderCreatedEvent;
import com.jayaprakash.oms.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * The only class in order-service that writes to Kafka.
 *
 * <p>Every message is keyed by order id, so all events for one order land on
 * the same partition and are therefore delivered in the order they were sent.
 * That guarantee is what makes the saga's state transitions safe.
 */
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * @param kafkaTemplate the shared JSON-serialising template
     */
    public OrderEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Starts the saga by announcing a new order.
     *
     * <p>Input: the created-order event. Processing: publishes it to
     * {@code order-created}, keyed by order id. Output: none; inventory-service
     * picks it up asynchronously.
     *
     * @param event the event describing the newly saved order
     */
    public void publishOrderCreated(OrderCreatedEvent event) {
        log.info("Publishing OrderCreatedEvent: orderId={} total={}", event.orderId(), event.totalAmount());
        kafkaTemplate.send(Topics.ORDER_CREATED, String.valueOf(event.orderId()), event);
    }

    /**
     * Triggers compensation after a failed payment.
     *
     * <p>Input: the cancellation event. Processing: publishes it to
     * {@code order-cancelled}. Output: none; inventory-service consumes it and
     * releases the stock it was holding.
     *
     * <p>This single call is what turns an optimistic multi-step process into a
     * correct saga.
     *
     * @param event the event describing the cancellation
     */
    public void publishOrderCancelled(OrderCancelledEvent event) {
        log.info("Publishing OrderCancelledEvent (compensation): orderId={} reason={}",
                event.orderId(), event.reason());
        kafkaTemplate.send(Topics.ORDER_CANCELLED, String.valueOf(event.orderId()), event);
    }
}
