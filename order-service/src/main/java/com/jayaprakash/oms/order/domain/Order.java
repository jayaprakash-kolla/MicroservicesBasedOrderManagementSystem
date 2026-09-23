package com.jayaprakash.oms.order.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An order, and the single source of truth for its status.
 *
 * <p>Inventory-service and payment-service each hold their own slice of the
 * transaction; this row is what ties them together and what a customer sees.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true, length = 64)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** Populated only when the order is cancelled, so the customer can be told why. */
    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderLineItem> items = new ArrayList<>();

    /** Required no-arg constructor for JPA. */
    protected Order() {
    }

    /**
     * Creates a new order in PENDING state and computes its total.
     *
     * <p>Input: the public order number, the customer, and the line items.
     * Processing: sums the line totals and stamps the timestamps. Output: an
     * unsaved {@link Order} ready to persist.
     *
     * @param orderNumber public reference for this order
     * @param customerId  who placed it
     * @param items       what was ordered, must not be empty
     */
    public Order(String orderNumber, String customerId, List<OrderLineItem> items) {
        this.orderNumber = orderNumber;
        this.customerId = customerId;
        this.items = new ArrayList<>(items);
        this.status = OrderStatus.PENDING;
        this.totalAmount = items.stream()
                .map(OrderLineItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Moves the order to a new status and refreshes the update timestamp.
     *
     * <p>Input: the target status. Processing: assigns it and re-stamps
     * {@code updatedAt}. Output: none. Callers are responsible for checking that
     * the transition is legal — see {@code OrderService}, which does exactly
     * that so duplicate Kafka messages cannot rewind a finished order.
     *
     * @param newStatus the status to move to
     */
    public void changeStatus(OrderStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    /**
     * Records why an order was cancelled.
     *
     * @param reason human-readable explanation from the failing service
     */
    public void setFailureReason(String reason) {
        this.failureReason = reason;
    }

    /**
     * Reports whether the order has reached a terminal state.
     *
     * <p>Input: none. Output: {@code true} for CONFIRMED or CANCELLED. Used as
     * the idempotency guard: a redelivered event must not reopen a closed order.
     *
     * @return {@code true} if no further transitions are allowed
     */
    public boolean isFinished() {
        return status == OrderStatus.CONFIRMED || status == OrderStatus.CANCELLED;
    }

    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getCustomerId() {
        return customerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<OrderLineItem> getItems() {
        return items;
    }
}
