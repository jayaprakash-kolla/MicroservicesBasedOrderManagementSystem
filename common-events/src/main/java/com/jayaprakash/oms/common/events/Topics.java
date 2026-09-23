package com.jayaprakash.oms.common.events;

/**
 * Every Kafka topic used by the saga, in one place.
 *
 * <p>One topic per event type. That is slightly more topics than a production
 * system would typically use, but it means each {@code @KafkaListener} method
 * handles exactly one type, which makes the flow far easier to follow and to
 * debug. Adding a listener for the wrong event becomes a compile error rather
 * than a message that silently goes nowhere.
 */
public final class Topics {

    /** Published by order-service when a new order is saved as PENDING. */
    public static final String ORDER_CREATED = "order-created";

    /** Published by order-service to compensate: tells inventory to release stock. */
    public static final String ORDER_CANCELLED = "order-cancelled";

    /** Published by inventory-service when stock was successfully held. */
    public static final String INVENTORY_RESERVED = "inventory-reserved";

    /** Published by inventory-service when stock was insufficient. */
    public static final String INVENTORY_FAILED = "inventory-failed";

    /** Published by payment-service when the charge succeeded. */
    public static final String PAYMENT_COMPLETED = "payment-completed";

    /** Published by payment-service when the charge was declined. */
    public static final String PAYMENT_FAILED = "payment-failed";

    /** Suffix used by the dead-letter recoverer, e.g. {@code order-created.DLT}. */
    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
        // utility class, never instantiated
    }
}
