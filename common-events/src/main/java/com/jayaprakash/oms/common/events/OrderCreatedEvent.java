package com.jayaprakash.oms.common.events;

import java.math.BigDecimal;
import java.util.List;

/**
 * Step 1 of the saga. Order-service has persisted a PENDING order and is asking
 * the rest of the system to make it real.
 *
 * @param orderId     internal database id of the order
 * @param orderNumber human-readable public reference
 * @param customerId  who placed it
 * @param items       what was ordered
 * @param totalAmount the amount payment-service will need to charge
 */
public record OrderCreatedEvent(
        Long orderId,
        String orderNumber,
        String customerId,
        List<OrderLineItemDto> items,
        BigDecimal totalAmount) {
}
