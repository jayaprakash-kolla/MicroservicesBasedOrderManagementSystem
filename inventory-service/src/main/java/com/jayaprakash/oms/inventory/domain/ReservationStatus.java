package com.jayaprakash.oms.inventory.domain;

/**
 * Lifecycle of a stock hold.
 */
public enum ReservationStatus {

    /** Stock is held for an order that has not completed yet. */
    RESERVED,

    /** The order failed and the stock has been given back. */
    RELEASED
}
