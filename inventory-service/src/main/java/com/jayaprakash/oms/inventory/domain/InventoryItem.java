package com.jayaprakash.oms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Stock level for one SKU.
 *
 * <p>Two counters rather than one: {@code availableQuantity} is what can still
 * be sold, {@code reservedQuantity} is what is held for orders that have not
 * been paid for yet. Keeping them separate is what makes releasing stock on a
 * failed payment an exact reversal rather than a guess.
 */
@Entity
@Table(name = "inventory_item")
public class InventoryItem {

    @Id
    @Column(name = "sku_code", nullable = false, length = 64)
    private String skuCode;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    /**
     * Optimistic lock. Two orders for the last unit of the same SKU will race;
     * this makes the loser fail with an exception (which Kafka then retries)
     * instead of both succeeding and overselling.
     */
    @Version
    @Column(name = "version")
    private Long version;

    /** Required no-arg constructor for JPA. */
    protected InventoryItem() {
    }

    /**
     * Creates a stock row with nothing yet reserved.
     *
     * @param skuCode           the product identifier
     * @param availableQuantity units in stock
     */
    public InventoryItem(String skuCode, int availableQuantity) {
        this.skuCode = skuCode;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = 0;
    }

    /**
     * Checks whether a requested quantity can be satisfied.
     *
     * <p>Input: how many units are wanted. Output: {@code true} if there is
     * enough available stock right now.
     *
     * @param quantity units requested
     * @return {@code true} when the reservation would succeed
     */
    public boolean hasStockFor(int quantity) {
        return availableQuantity >= quantity;
    }

    /**
     * Moves units from available to reserved.
     *
     * <p>Input: units to hold. Processing: decrements available and increments
     * reserved. Output: none. Throws rather than going negative, because silent
     * negative stock is far harder to debug than a loud failure.
     *
     * @param quantity units to reserve
     * @throws IllegalStateException if there is not enough available stock
     */
    public void reserve(int quantity) {
        if (!hasStockFor(quantity)) {
            throw new IllegalStateException(
                    "Cannot reserve " + quantity + " of " + skuCode + ", only " + availableQuantity + " available");
        }
        this.availableQuantity -= quantity;
        this.reservedQuantity += quantity;
    }

    /**
     * Moves units back from reserved to available. The exact inverse of
     * {@link #reserve(int)}, used when a saga is compensated.
     *
     * <p>Input: units to release. Processing: decrements reserved and increments
     * available. Output: none.
     *
     * @param quantity units to give back
     */
    public void release(int quantity) {
        this.reservedQuantity -= quantity;
        this.availableQuantity += quantity;
    }

    /**
     * Converts reserved units into a permanent reduction. Called when an order
     * is confirmed — not wired up in this build, but this is where it goes.
     *
     * @param quantity units to finalise
     */
    public void commitReservation(int quantity) {
        this.reservedQuantity -= quantity;
    }

    /**
     * Adds new stock, for example after a delivery from a supplier.
     *
     * @param quantity units to add to available stock
     */
    public void addStock(int quantity) {
        this.availableQuantity += quantity;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }
}
