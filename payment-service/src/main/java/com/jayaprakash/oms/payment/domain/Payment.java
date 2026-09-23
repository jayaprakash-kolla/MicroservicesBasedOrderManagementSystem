package com.jayaprakash.oms.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A charge attempt against one order.
 *
 * <p>{@code orderId} is unique, which is the database enforcing idempotency:
 * even if two threads process a redelivered event simultaneously, only one row
 * can exist, so a customer cannot be charged twice for the same order.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_reference", nullable = false, unique = true, length = 64)
    private String paymentReference;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    /** Populated only for declines, so the customer can be told why. */
    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required no-arg constructor for JPA. */
    protected Payment() {
    }

    /**
     * Records a charge attempt and its outcome.
     *
     * @param paymentReference public reference for the transaction
     * @param orderId          the order being paid for
     * @param customerId       who was charged
     * @param amount           how much
     * @param status           whether the gateway accepted it
     * @param failureReason    why it was declined, or {@code null} on success
     */
    public Payment(String paymentReference,
                   Long orderId,
                   String customerId,
                   BigDecimal amount,
                   PaymentStatus status,
                   String failureReason) {
        this.paymentReference = paymentReference;
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.failureReason = failureReason;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
