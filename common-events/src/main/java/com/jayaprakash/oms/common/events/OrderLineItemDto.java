package com.jayaprakash.oms.common.events;

import java.math.BigDecimal;

/**
 * One line of an order as it travels between services.
 *
 * <p>This is the shared wire shape. Each service maps it into whatever internal
 * entity it needs — order-service stores it as a row, inventory-service only
 * looks at {@code skuCode} and {@code quantity}.
 *
 * @param skuCode  the product identifier
 * @param quantity how many units were ordered
 * @param price    unit price at the time of ordering
 */
public record OrderLineItemDto(String skuCode, int quantity, BigDecimal price) {
}
