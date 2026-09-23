package com.jayaprakash.oms.common.events;

import java.math.BigDecimal;

/**
 * Step 3 (success). The customer has been charged; the saga completes and the
 * order is confirmed.
 *
 * @param orderId   the order that was paid for
 * @param paymentId payment-service's own id for the transaction
 * @param amount    what was actually charged
 */
public record PaymentCompletedEvent(Long orderId, String paymentId, BigDecimal amount) {
}
