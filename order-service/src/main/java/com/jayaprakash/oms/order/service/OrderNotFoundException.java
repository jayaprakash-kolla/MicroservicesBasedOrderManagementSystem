package com.jayaprakash.oms.order.service;

/**
 * Thrown when an order id does not exist.
 *
 * <p>Mapped to HTTP 404 by the global exception handler, and treated as a
 * retryable failure on the Kafka side — an event may briefly arrive before the
 * order's transaction is visible, so a retry is often the right answer.
 */
public class OrderNotFoundException extends RuntimeException {

    /**
     * @param message description including the offending order id
     */
    public OrderNotFoundException(String message) {
        super(message);
    }
}
