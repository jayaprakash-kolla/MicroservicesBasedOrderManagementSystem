package com.jayaprakash.oms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A record of stock held for one order line.
 *
 * <p>This table is the reason compensation is possible. Without it, an
 * {@code OrderCancelledEvent} would arrive saying "give the stock back" and
 * inventory-service would have no idea how much to give back, or for which SKUs.
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "sku_code", nullable = false, length = 64)
    private String skuCode;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required no-arg constructor for JPA. */
    protected Reservation() {
    }

    /**
     * Creates a hold in RESERVED state.
     *
     * @param orderId  the order the stock is held for
     * @param skuCode  the product held
     * @param quantity how many units
     */
    public Reservation(Long orderId, String skuCode, int quantity) {
        this.orderId = orderId;
        this.skuCode = skuCode;
        this.quantity = quantity;
        this.status = ReservationStatus.RESERVED;
        this.createdAt = Instant.now();
    }

    /**
     * Marks this hold as released, so a duplicate cancellation cannot release
     * the same stock twice.
     */
    public void markReleased() {
        this.status = ReservationStatus.RELEASED;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public int getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
