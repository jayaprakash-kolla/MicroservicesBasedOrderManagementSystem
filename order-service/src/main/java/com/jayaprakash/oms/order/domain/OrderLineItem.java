package com.jayaprakash.oms.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One product line on an order, stored in order-service's own database.
 */
@Entity
@Table(name = "order_line_item")
public class OrderLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku_code", nullable = false, length = 64)
    private String skuCode;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    /** Required no-arg constructor for JPA. */
    protected OrderLineItem() {
    }

    /**
     * Creates a line item.
     *
     * @param skuCode  product identifier
     * @param quantity units ordered
     * @param price    unit price
     */
    public OrderLineItem(String skuCode, int quantity, BigDecimal price) {
        this.skuCode = skuCode;
        this.quantity = quantity;
        this.price = price;
    }

    /**
     * Calculates this line's contribution to the order total.
     *
     * <p>Input: none (uses its own price and quantity).
     * Output: {@code price * quantity} as a {@link BigDecimal}, so money maths
     * never goes through a double.
     *
     * @return the line subtotal
     */
    public BigDecimal lineTotal() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }

    public Long getId() {
        return id;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }
}
