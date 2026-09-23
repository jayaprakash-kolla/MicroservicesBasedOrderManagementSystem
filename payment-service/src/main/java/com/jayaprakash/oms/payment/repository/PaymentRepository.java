package com.jayaprakash.oms.payment.repository;

import com.jayaprakash.oms.payment.domain.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Database access for payments. Payment-service owns this schema; nobody else
 * reads it.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * Looks up the payment for an order.
     *
     * <p>Input: an order id. Output: the payment if one exists. Used as the
     * idempotency check before attempting a charge.
     *
     * @param orderId the order to look up
     * @return the payment, if any
     */
    Optional<Payment> findByOrderId(Long orderId);
}
