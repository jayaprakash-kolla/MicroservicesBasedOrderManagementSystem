package com.jayaprakash.oms.common.events;

import java.math.BigDecimal;

/**
 * Step 2 (success). Stock has been held for this order; payment may proceed.
 *
 * <p>{@code totalAmount} is carried forward so payment-service does not have to
 * call back into order-service to find out what to charge. Passing the data the
 * next step needs is what keeps the services independent.
 *
 * @param orderId       the order this relates to
 * @param reservationId inventory-service's own id for the hold it created
 * @param customerId    forwarded so payment can record who was charged
 * @param totalAmount   the amount to charge
 */
public record InventoryReservedEvent(
        Long orderId,
        Long reservationId,
        String customerId,
        BigDecimal totalAmount) {
}
