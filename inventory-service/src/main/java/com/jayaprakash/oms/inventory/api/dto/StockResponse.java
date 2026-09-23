package com.jayaprakash.oms.inventory.api.dto;

import com.jayaprakash.oms.inventory.domain.InventoryItem;

/**
 * Stock level for one SKU as returned by the API.
 *
 * @param skuCode           the product
 * @param availableQuantity units that can still be sold
 * @param reservedQuantity  units held for in-flight orders
 */
public record StockResponse(String skuCode, int availableQuantity, int reservedQuantity) {

    /**
     * Converts an entity into its API representation.
     *
     * <p>Input: an {@link InventoryItem}. Output: the DTO, keeping the JPA
     * entity (and its version column) out of the HTTP layer.
     *
     * @param item the stock row
     * @return the DTO to serialise
     */
    public static StockResponse from(InventoryItem item) {
        return new StockResponse(
                item.getSkuCode(), item.getAvailableQuantity(), item.getReservedQuantity());
    }
}
