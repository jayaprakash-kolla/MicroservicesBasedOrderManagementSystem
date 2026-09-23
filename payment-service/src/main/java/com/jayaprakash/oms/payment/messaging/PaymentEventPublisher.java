package com.jayaprakash.oms.payment.messaging;

import com.jayaprakash.oms.common.events.PaymentCompletedEvent;
import com.jayaprakash.oms.common.events.PaymentFailedEvent;
import com.jayaprakash.oms.common.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Announces the outcome of a charge back to the saga.
 */
@Component
public class PaymentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * @param kafkaTemplate the shared JSON-serialising template
     */
    public PaymentEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Announces a successful charge, which completes the saga.
     *
     * <p>Input: the success event. Processing: publishes to
     * {@code payment-completed}, keyed by order id. Output: none.
     *
     * @param event the payment confirmation
     */
    public void publishPaymentCompleted(PaymentCompletedEvent event) {
        log.info("Publishing PaymentCompletedEvent: orderId={} paymentId={}",
                event.orderId(), event.paymentId());
        kafkaTemplate.send(Topics.PAYMENT_COMPLETED, String.valueOf(event.orderId()), event);
    }

    /**
     * Announces a decline, which starts the unwind.
     *
     * <p>Input: the failure event. Processing: publishes to
     * {@code payment-failed}. Output: none. Order-service reacts by cancelling
     * the order and telling inventory to release the stock.
     *
     * @param event the decline notice
     */
    public void publishPaymentFailed(PaymentFailedEvent event) {
        log.info("Publishing PaymentFailedEvent: orderId={} reason={}", event.orderId(), event.reason());
        kafkaTemplate.send(Topics.PAYMENT_FAILED, String.valueOf(event.orderId()), event);
    }
}
