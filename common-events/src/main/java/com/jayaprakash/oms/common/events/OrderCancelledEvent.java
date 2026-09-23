package com.jayaprakash.oms.common.events;

/**
 * The compensating event. Published by order-service after a payment failure so
 * inventory-service can release the stock it was holding.
 *
 * <p>This is what makes the saga correct rather than merely optimistic: without
 * it, a declined payment would silently leak inventory forever.
 *
 * @param orderId the order being unwound
 * @param reason  why it is being unwound, copied from the failure that caused it
 */
public record OrderCancelledEvent(Long orderId, String reason) {
}
