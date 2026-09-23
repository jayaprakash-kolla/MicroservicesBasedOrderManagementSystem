package com.jayaprakash.oms.order.domain;

/**
 * The order lifecycle, which is also the saga's progress marker.
 *
 * <p>Legal transitions:
 * <pre>
 *   PENDING ──→ INVENTORY_RESERVED ──→ CONFIRMED
 *      │                 │
 *      └────→ CANCELLED ←┘
 * </pre>
 * Anything else is a bug or a duplicate message, and the service logs and
 * ignores it rather than corrupting the record.
 */
public enum OrderStatus {

    /** Saved, event published, waiting on inventory. */
    PENDING,

    /** Stock is held; waiting on payment. */
    INVENTORY_RESERVED,

    /** Paid for. Terminal, successful. */
    CONFIRMED,

    /** Inventory or payment failed. Terminal, unsuccessful. */
    CANCELLED
}
