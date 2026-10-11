package com.jayaprakash.oms.monitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.jayaprakash.oms.common.events.Topics;
import com.jayaprakash.oms.monitor.domain.SagaEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Turns a raw Kafka record into a {@link SagaEvent}.
 *
 * <p>The topic name alone says which event a message is and who published it,
 * because the system uses one topic per event type. That is the same design
 * choice that lets each business listener handle exactly one type, and it is
 * what lets this class work without deserialising into the event records.
 */
@Component
public class SagaEventFactory {

    private static final Logger log = LoggerFactory.getLogger(SagaEventFactory.class);

    /** Event type and publishing service for every saga topic. */
    private static final Map<String, TopicInfo> CATALOG = Map.of(
            Topics.ORDER_CREATED, new TopicInfo("OrderCreatedEvent", "order-service"),
            Topics.ORDER_CANCELLED, new TopicInfo("OrderCancelledEvent", "order-service"),
            Topics.INVENTORY_RESERVED, new TopicInfo("InventoryReservedEvent", "inventory-service"),
            Topics.INVENTORY_FAILED, new TopicInfo("InventoryFailedEvent", "inventory-service"),
            Topics.PAYMENT_COMPLETED, new TopicInfo("PaymentCompletedEvent", "payment-service"),
            Topics.PAYMENT_FAILED, new TopicInfo("PaymentFailedEvent", "payment-service"));

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper Spring's shared JSON mapper
     */
    public SagaEventFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Builds the event the browser will see.
     *
     * <p>Input: the parts of a Kafka record. Processing: looks the topic up in
     * the catalogue (stripping {@code .DLT} first, so dead-lettered copies are
     * recognised), parses the body as JSON, and finds the order id in the body
     * or, failing that, in the message key. Output: the event with sequence 0,
     * or empty for a topic that is not part of the saga.
     *
     * <p>A body that is not valid JSON is kept as plain text rather than
     * dropped: a monitor that silently hides malformed messages would hide
     * exactly the messages worth looking at.
     *
     * @param topic     the topic the record came from
     * @param key       the record key, which the services set to the order id
     * @param value     the record body, a JSON document
     * @param timestamp the record timestamp in epoch milliseconds
     * @param partition the partition it was read from
     * @param offset    its offset within that partition
     * @return the event, or empty if the topic is unknown
     */
    public Optional<SagaEvent> fromRecord(String topic,
                                          String key,
                                          String value,
                                          long timestamp,
                                          int partition,
                                          long offset) {

        boolean deadLetter = topic.endsWith(Topics.DLT_SUFFIX);
        String baseTopic = deadLetter
                ? topic.substring(0, topic.length() - Topics.DLT_SUFFIX.length())
                : topic;

        TopicInfo info = CATALOG.get(baseTopic);
        if (info == null) {
            log.debug("Ignoring record from non-saga topic {}", topic);
            return Optional.empty();
        }

        JsonNode payload = parse(value);

        return Optional.of(new SagaEvent(
                0L,
                topic,
                info.eventType(),
                info.publishedBy(),
                findOrderId(payload, key),
                deadLetter,
                payload,
                Instant.ofEpochMilli(timestamp),
                partition,
                offset));
    }

    /**
     * Parses a record body, falling back to plain text.
     *
     * @param value the raw body, possibly {@code null}
     * @return the parsed JSON, or a text node holding the raw body
     */
    private JsonNode parse(String value) {
        if (value == null) {
            return TextNode.valueOf("");
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            log.warn("Record body is not JSON, keeping it as text: {}", exception.getOriginalMessage());
            return TextNode.valueOf(value);
        }
    }

    /**
     * Finds which order a message belongs to.
     *
     * <p>Input: the parsed body and the record key. Output: the {@code orderId}
     * field if the body has a numeric one, otherwise the key if it is numeric,
     * otherwise {@code null}.
     *
     * @param payload the parsed body
     * @param key     the record key
     * @return the order id, or {@code null}
     */
    private Long findOrderId(JsonNode payload, String key) {
        JsonNode orderId = payload.path("orderId");
        if (orderId.canConvertToLong()) {
            return orderId.asLong();
        }
        if (key != null) {
            try {
                return Long.valueOf(key.trim());
            } catch (NumberFormatException ignored) {
                // Not a numeric key, so there is nothing more to try.
            }
        }
        return null;
    }

    /**
     * What the catalogue knows about one topic.
     *
     * @param eventType   the event class published on it
     * @param publishedBy the service that publishes it
     */
    private record TopicInfo(String eventType, String publishedBy) {
    }
}
