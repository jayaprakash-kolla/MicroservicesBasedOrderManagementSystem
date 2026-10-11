package com.jayaprakash.oms.monitor.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayaprakash.oms.common.events.Topics;
import com.jayaprakash.oms.monitor.domain.SagaEvent;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SagaEventFactory}: topic recognition, order-id
 * extraction, and tolerance of messages that are not what they should be.
 */
class SagaEventFactoryTest {

    private SagaEventFactory factory;

    @BeforeEach
    void setUp() {
        factory = new SagaEventFactory(new ObjectMapper());
    }

    @Test
    @DisplayName("recognises a saga topic and reads the order id from the body")
    void mapsKnownTopic() {
        Optional<SagaEvent> event = factory.fromRecord(
                Topics.PAYMENT_FAILED, "7",
                "{\"orderId\":7,\"reason\":\"Card declined\"}",
                1_700_000_000_000L, 0, 12L);

        assertThat(event).isPresent();
        assertThat(event.get().eventType()).isEqualTo("PaymentFailedEvent");
        assertThat(event.get().publishedBy()).isEqualTo("payment-service");
        assertThat(event.get().orderId()).isEqualTo(7L);
        assertThat(event.get().deadLetter()).isFalse();
        assertThat(event.get().payload().path("reason").asText()).isEqualTo("Card declined");
        assertThat(event.get().occurredAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(event.get().offset()).isEqualTo(12L);
    }

    @Test
    @DisplayName("names the publisher correctly for every one of the six topics")
    void mapsEveryTopicToItsPublisher() {
        assertThat(publisherOf(Topics.ORDER_CREATED)).isEqualTo("order-service");
        assertThat(publisherOf(Topics.ORDER_CANCELLED)).isEqualTo("order-service");
        assertThat(publisherOf(Topics.INVENTORY_RESERVED)).isEqualTo("inventory-service");
        assertThat(publisherOf(Topics.INVENTORY_FAILED)).isEqualTo("inventory-service");
        assertThat(publisherOf(Topics.PAYMENT_COMPLETED)).isEqualTo("payment-service");
        assertThat(publisherOf(Topics.PAYMENT_FAILED)).isEqualTo("payment-service");
    }

    /**
     * Builds an event for a topic and returns who publishes it.
     *
     * @param topic the topic to look up
     * @return the publishing service
     */
    private String publisherOf(String topic) {
        return factory.fromRecord(topic, "1", "{\"orderId\":1}", 0L, 0, 0L)
                .orElseThrow()
                .publishedBy();
    }

    @Test
    @DisplayName("flags a dead-lettered copy while still recognising the event")
    void recognisesDeadLetterTopic() {
        Optional<SagaEvent> event = factory.fromRecord(
                Topics.ORDER_CREATED + Topics.DLT_SUFFIX, "3", "{\"orderId\":3}", 0L, 0, 0L);

        assertThat(event).isPresent();
        assertThat(event.get().deadLetter()).isTrue();
        assertThat(event.get().eventType()).isEqualTo("OrderCreatedEvent");
        assertThat(event.get().topic()).isEqualTo("order-created.DLT");
    }

    @Test
    @DisplayName("ignores topics that are not part of the saga")
    void ignoresUnknownTopic() {
        assertThat(factory.fromRecord("orders-audit", "1", "{}", 0L, 0, 0L)).isEmpty();
    }

    @Test
    @DisplayName("falls back to the message key when the body has no order id")
    void usesKeyWhenBodyHasNoOrderId() {
        SagaEvent event = factory.fromRecord(
                Topics.INVENTORY_FAILED, "42", "{\"reason\":\"x\"}", 0L, 0, 0L).orElseThrow();

        assertThat(event.orderId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("keeps a body that is not JSON as text instead of dropping the message")
    void keepsMalformedBodyAsText() {
        SagaEvent event = factory.fromRecord(
                Topics.ORDER_CREATED, "not-a-number", "this is not json", 0L, 0, 0L).orElseThrow();

        assertThat(event.payload().isTextual()).isTrue();
        assertThat(event.payload().asText()).isEqualTo("this is not json");
        assertThat(event.orderId()).isNull();
    }
}
