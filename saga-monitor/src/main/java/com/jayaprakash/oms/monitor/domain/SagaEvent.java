package com.jayaprakash.oms.monitor.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * One saga message as observed on Kafka, in the shape the browser receives.
 *
 * @param sequence    position in this monitor's stream, starting at 1; also the SSE event id
 * @param topic       the Kafka topic it was read from, e.g. {@code payment-failed}
 * @param eventType   the event class it represents, e.g. {@code PaymentFailedEvent}
 * @param publishedBy the service that publishes this event type
 * @param orderId     the order it belongs to, or {@code null} if it could not be determined
 * @param deadLetter  {@code true} when read from a {@code .DLT} topic, i.e. a consumer gave up on it
 * @param payload     the message body exactly as published
 * @param occurredAt  the Kafka record timestamp, i.e. when it was published
 * @param partition   the partition it was read from
 * @param offset      its offset within that partition
 */
public record SagaEvent(
        long sequence,
        String topic,
        String eventType,
        String publishedBy,
        Long orderId,
        boolean deadLetter,
        JsonNode payload,
        Instant occurredAt,
        int partition,
        long offset
) {

    /**
     * Returns a copy of this event with its stream position assigned.
     *
     * <p>Input: the sequence number. Output: an identical event carrying it.
     * Records are immutable, so the store stamps the number by copying.
     *
     * @param newSequence the position in the monitor's stream
     * @return the stamped event
     */
    public SagaEvent withSequence(long newSequence) {
        return new SagaEvent(newSequence, topic, eventType, publishedBy, orderId,
                deadLetter, payload, occurredAt, partition, offset);
    }
}
