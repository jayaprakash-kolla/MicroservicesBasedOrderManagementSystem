package com.jayaprakash.oms.common.events;

/**
 * Step 3 (failure). This is the interesting case: inventory was already
 * reserved, so the saga must now be unwound.
 *
 * <p>Order-service reacts by cancelling the order AND publishing an
 * {@link OrderCancelledEvent}, which is the compensating action that gives the
 * stock back.
 *
 * @param orderId the order whose payment was declined
 * @param reason  why it was declined
 */
public record PaymentFailedEvent(Long orderId, String reason) {
}
