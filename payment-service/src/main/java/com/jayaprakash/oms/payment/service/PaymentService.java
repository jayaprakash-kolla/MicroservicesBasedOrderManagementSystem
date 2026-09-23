package com.jayaprakash.oms.payment.service;

import com.jayaprakash.oms.common.events.InventoryReservedEvent;
import com.jayaprakash.oms.common.events.PaymentCompletedEvent;
import com.jayaprakash.oms.common.events.PaymentFailedEvent;
import com.jayaprakash.oms.payment.domain.Payment;
import com.jayaprakash.oms.payment.domain.PaymentStatus;
import com.jayaprakash.oms.payment.messaging.PaymentEventPublisher;
import com.jayaprakash.oms.payment.repository.PaymentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga step 3: charge the customer and announce the result.
 *
 * <p>Every path through this class ends in exactly one published event. If it
 * ever ended in zero, the order would sit in INVENTORY_RESERVED forever with
 * stock held against it — which is the classic way a saga goes wrong.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentEventPublisher eventPublisher;

    /**
     * @param paymentRepository stores charge attempts
     * @param paymentGateway    the card processor
     * @param eventPublisher    announces the result back to the saga
     */
    public PaymentService(PaymentRepository paymentRepository,
                          PaymentGateway paymentGateway,
                          PaymentEventPublisher eventPublisher) {
        this.paymentRepository = paymentRepository;
        this.paymentGateway = paymentGateway;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Charges the customer for an order whose stock is now held.
     *
     * <p>Input: the {@link InventoryReservedEvent}, which carries the customer
     * and the amount so no call back to order-service is needed. Processing:
     * skips the work if this order was already charged, otherwise calls the
     * gateway, records the attempt, and publishes success or failure. Output:
     * none directly — the result is an event on Kafka.
     *
     * @param event the reservation confirmation that authorises the charge
     */
    @Transactional
    public void processPayment(InventoryReservedEvent event) {

        // Idempotency: a redelivered event must not charge the customer twice.
        Optional<Payment> existing = paymentRepository.findByOrderId(event.orderId());
        if (existing.isPresent()) {
            log.warn("Order {} already has payment {} ({}), ignoring duplicate event",
                    event.orderId(), existing.get().getPaymentReference(), existing.get().getStatus());
            return;
        }

        Optional<String> declineReason = paymentGateway.charge(event.customerId(), event.totalAmount());
        String reference = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        if (declineReason.isPresent()) {
            paymentRepository.save(new Payment(
                    reference, event.orderId(), event.customerId(), event.totalAmount(),
                    PaymentStatus.DECLINED, declineReason.get()));

            log.info("Payment DECLINED for order {}: {}", event.orderId(), declineReason.get());

            // Order-service will cancel the order and trigger inventory release.
            eventPublisher.publishPaymentFailed(
                    new PaymentFailedEvent(event.orderId(), declineReason.get()));
            return;
        }

        paymentRepository.save(new Payment(
                reference, event.orderId(), event.customerId(), event.totalAmount(),
                PaymentStatus.COMPLETED, null));

        log.info("Payment COMPLETED for order {}: {}", event.orderId(), reference);

        eventPublisher.publishPaymentCompleted(new PaymentCompletedEvent(
                event.orderId(), reference, event.totalAmount()));
    }

    /**
     * Lists every recorded charge attempt.
     *
     * <p>Input: none. Output: all payments, used by the REST endpoint to check
     * what the gateway decided.
     *
     * @return all payments
     */
    @Transactional(readOnly = true)
    public List<Payment> listPayments() {
        return paymentRepository.findAll();
    }

    /**
     * Finds the payment for one order.
     *
     * <p>Input: an order id. Output: the payment, if the order got that far.
     *
     * @param orderId the order to look up
     * @return the payment, if any
     */
    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderId(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }
}
