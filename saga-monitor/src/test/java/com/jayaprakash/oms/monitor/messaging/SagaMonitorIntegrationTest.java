package com.jayaprakash.oms.monitor.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayaprakash.oms.common.config.KafkaMessagingConfig;
import com.jayaprakash.oms.common.events.InventoryReservedEvent;
import com.jayaprakash.oms.common.events.OrderCancelledEvent;
import com.jayaprakash.oms.common.events.OrderCreatedEvent;
import com.jayaprakash.oms.common.events.OrderLineItemDto;
import com.jayaprakash.oms.common.events.PaymentFailedEvent;
import com.jayaprakash.oms.common.events.Topics;
import com.jayaprakash.oms.monitor.domain.SagaEvent;
import com.jayaprakash.oms.monitor.service.SagaEventStore;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the monitor end to end against an embedded Kafka broker.
 *
 * <p>The test publishes with the business services' own Kafka configuration
 * ({@link KafkaMessagingConfig}) and the real event records, so what the
 * monitor reads here is byte-for-byte what it reads in a running system.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {
                Topics.ORDER_CREATED, Topics.ORDER_CANCELLED,
                Topics.INVENTORY_RESERVED, Topics.INVENTORY_FAILED,
                Topics.PAYMENT_COMPLETED, Topics.PAYMENT_FAILED
        })
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
class SagaMonitorIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** Publishes exactly the way order, inventory, and payment service do. */
    @TestConfiguration
    @Import(KafkaMessagingConfig.class)
    static class RealPublisherConfig {
    }

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private SagaEventStore eventStore;

    @Autowired
    private MockMvc mockMvc;

    /**
     * Publishes an order-created event the way order-service does.
     *
     * @param orderId the order id, also used as the message key
     */
    private void sendOrderCreated(long orderId) {
        kafkaTemplate.send(Topics.ORDER_CREATED, String.valueOf(orderId), new OrderCreatedEvent(
                orderId, "ORD-" + orderId, "cust-1",
                List.of(new OrderLineItemDto("LAPTOP-001", 2, new BigDecimal("500.00"))),
                new BigDecimal("1000.00")));
    }

    @Test
    @DisplayName("an event published by a business service is observed with its type, publisher, and body")
    void observesPublishedEvent() {
        sendOrderCreated(42L);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(eventStore.recent(42L, 10)).hasSize(1));

        SagaEvent event = eventStore.recent(42L, 10).get(0);
        assertThat(event.eventType()).isEqualTo("OrderCreatedEvent");
        assertThat(event.publishedBy()).isEqualTo("order-service");
        assertThat(event.deadLetter()).isFalse();
        assertThat(event.payload().path("orderNumber").asText()).isEqualTo("ORD-42");
        assertThat(event.payload().path("items").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("a declined payment's whole saga, including the compensation, is observed")
    void observesCompensationPath() {
        sendOrderCreated(43L);
        kafkaTemplate.send(Topics.INVENTORY_RESERVED, "43",
                new InventoryReservedEvent(43L, 1L, "cust-1", new BigDecimal("1000.00")));
        kafkaTemplate.send(Topics.PAYMENT_FAILED, "43",
                new PaymentFailedEvent(43L, "Card declined"));
        kafkaTemplate.send(Topics.ORDER_CANCELLED, "43",
                new OrderCancelledEvent(43L, "Card declined"));

        // Different topics are not ordered relative to each other, so only the
        // set of events is asserted, not their sequence.
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(eventStore.recent(43L, 10))
                        .extracting(SagaEvent::eventType)
                        .containsExactlyInAnyOrder(
                                "OrderCreatedEvent", "InventoryReservedEvent",
                                "PaymentFailedEvent", "OrderCancelledEvent"));
    }

    @Test
    @DisplayName("GET /api/v1/saga/events returns observed events as JSON")
    void exposesRecentEventsOverHttp() throws Exception {
        sendOrderCreated(44L);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(eventStore.recent(44L, 10)).hasSize(1));

        mockMvc.perform(get("/api/v1/saga/events").param("orderId", "44"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("OrderCreatedEvent"))
                .andExpect(jsonPath("$[0].orderId").value(44))
                .andExpect(jsonPath("$[0].payload.customerId").value("cust-1"))
                .andExpect(jsonPath("$[0].occurredAt").isString());
    }

    @Test
    @DisplayName("GET /api/v1/saga/stream opens a long-lived event stream")
    void opensEventStream() throws Exception {
        mockMvc.perform(get("/api/v1/saga/stream"))
                .andExpect(request().asyncStarted());
    }
}
