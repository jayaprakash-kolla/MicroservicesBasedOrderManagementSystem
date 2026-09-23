package com.jayaprakash.oms.payment.messaging;

import com.jayaprakash.oms.common.events.InventoryReservedEvent;
import com.jayaprakash.oms.common.events.Topics;
import com.jayaprakash.oms.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The single event payment-service reacts to.
 *
 * <p>It consumes {@code inventory-reserved} in its own consumer group, so both
 * it and order-service receive every copy of that event independently — that is
 * exactly what consumer groups are for.
 */
@Component
public class PaymentSagaListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentSagaListener.class);

    private final PaymentService paymentService;

    /**
     * @param paymentService where the charge logic lives
     */
    public PaymentSagaListener(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Stock is held, so the customer can safely be charged.
     *
     * <p>Input: an {@link InventoryReservedEvent}. Processing: delegates to the
     * service, which publishes either a completed or a failed event. Output: none.
     *
     * @param event the reservation confirmation
     */
    @KafkaListener(topics = Topics.INVENTORY_RESERVED, groupId = "payment-service")
    public void onInventoryReserved(InventoryReservedEvent event) {
        log.info("Received InventoryReservedEvent: orderId={} amount={}",
                event.orderId(), event.totalAmount());
        paymentService.processPayment(event);
    }
}
