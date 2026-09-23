package com.jayaprakash.oms.inventory.messaging;

import com.jayaprakash.oms.common.events.InventoryFailedEvent;
import com.jayaprakash.oms.common.events.InventoryReservedEvent;
import com.jayaprakash.oms.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes the outcome of a reservation attempt back to the saga.
 */
@Component
public class InventoryEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * @param kafkaTemplate the shared JSON-serialising template
     */
    public InventoryEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Announces that stock is held and the saga may continue.
     *
     * <p>Input: the success event. Processing: publishes to
     * {@code inventory-reserved}, keyed by order id. Output: none. Both
     * order-service and payment-service consume this, in separate consumer
     * groups, so each gets its own copy.
     *
     * @param event the reservation confirmation
     */
    public void publishInventoryReserved(InventoryReservedEvent event) {
        log.info("Publishing InventoryReservedEvent: orderId={}", event.orderId());
        kafkaTemplate.send(Topics.INVENTORY_RESERVED, String.valueOf(event.orderId()), event);
    }

    /**
     * Announces that the order cannot be fulfilled.
     *
     * <p>Input: the failure event. Processing: publishes to
     * {@code inventory-failed}. Output: none. Only order-service listens; the
     * saga ends here with no compensation needed.
     *
     * @param event the failure notice
     */
    public void publishInventoryFailed(InventoryFailedEvent event) {
        log.info("Publishing InventoryFailedEvent: orderId={} reason={}", event.orderId(), event.reason());
        kafkaTemplate.send(Topics.INVENTORY_FAILED, String.valueOf(event.orderId()), event);
    }
}
