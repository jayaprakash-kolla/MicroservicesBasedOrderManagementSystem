package com.jayaprakash.oms.payment.api.dto;

import com.jayaprakash.oms.payment.domain.Payment;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment as returned by the API.
 *
 * @param paymentReference public transaction reference
 * @param orderId          the order paid for
 * @param customerId       who was charged
 * @param amount           how much
 * @param status           COMPLETED or DECLINED
 * @param failureReason    why it was declined, {@code null} otherwise
 * @param createdAt        when the attempt was made
 */
public record PaymentResponse(

        String paymentReference,
        Long orderId,
        String customerId,
        BigDecimal amount,
        String status,
        String failureReason,
        Instant createdAt
) {

    /**
     * Converts an entity into its API representation.
     *
     * @param payment the persisted payment
     * @return the DTO to serialise
     */
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getPaymentReference(),
                payment.getOrderId(),
                payment.getCustomerId(),
                payment.getAmount(),
                payment.getStatus().name(),
                payment.getFailureReason(),
                payment.getCreatedAt());
    }
}
