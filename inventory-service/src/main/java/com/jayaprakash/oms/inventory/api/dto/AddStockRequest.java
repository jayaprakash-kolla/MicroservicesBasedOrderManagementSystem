package com.jayaprakash.oms.inventory.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/inventory}.
 *
 * @param skuCode  the product to restock
 * @param quantity how many units to add, at least 1
 */
public record AddStockRequest(

        @NotBlank(message = "skuCode is required")
        String skuCode,

        @Min(value = 1, message = "quantity must be at least 1")
        int quantity
) {
}
