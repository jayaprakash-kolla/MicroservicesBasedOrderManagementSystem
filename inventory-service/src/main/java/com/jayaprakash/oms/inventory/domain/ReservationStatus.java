package com.jayaprakash.oms.inventory.domain;

/**
 * Lifecycle of a stock hold.
 *
 * <pre>
 *   RESERVED ──→ COMMITTED   (payment completed: the units are sold)
 *       │
 *       └─────→ RELEASED    (order cancelled: the units go back on sale)
 * </pre>
 * Both outcomes are terminal, so a redelivered event can never move a hold twice.
 */
public enum ReservationStatus {

    /** Stock is held for an order that has not completed yet. */
    RESERVED,

    /** The order was paid for and the held units have left stock for good. */
    COMMITTED,

    /** The order failed and the stock has been given back. */
    RELEASED
}
