package com.jayaprakash.oms.common.events;

/**
 * Step 2 (failure). Not enough stock, so the saga stops here.
 *
 * <p>No compensation is needed: nothing was reserved and nothing was charged.
 * Order-service simply marks the order CANCELLED.
 *
 * @param orderId the order that could not be fulfilled
 * @param reason  human-readable explanation, surfaced on the order record
 */
public record InventoryFailedEvent(Long orderId, String reason) {
}
