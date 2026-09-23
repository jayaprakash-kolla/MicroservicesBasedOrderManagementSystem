package com.jayaprakash.oms.order.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * One requested line on an incoming order.
 *
 * @param skuCode  product identifier, required
 * @param quantity units wanted, must be at least 1
 * @param price    unit price, must be positive
 */
public record OrderLineItemRequest(

        @NotBlank(message = "skuCode is required")
        String skuCode,

        @Min(value = 1, message = "quantity must be at least 1")
        int quantity,

        @NotNull(message = "price is required")
        @DecimalMin(value = "0.01", message = "price must be greater than zero")
        BigDecimal price
) {
}
