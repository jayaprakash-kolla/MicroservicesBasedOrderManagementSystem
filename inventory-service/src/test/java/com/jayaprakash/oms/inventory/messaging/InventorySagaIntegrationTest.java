package com.jayaprakash.oms.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayaprakash.oms.common.events.InventoryFailedEvent;
import com.jayaprakash.oms.common.events.InventoryReservedEvent;
import com.jayaprakash.oms.common.events.OrderCancelledEvent;
import com.jayaprakash.oms.common.events.OrderCreatedEvent;
import com.jayaprakash.oms.common.events.OrderLineItemDto;
import com.jayaprakash.oms.common.events.PaymentCompletedEvent;
import com.jayaprakash.oms.common.events.Topics;
import com.jayaprakash.oms.inventory.domain.InventoryItem;
import com.jayaprakash.oms.inventory.domain.ReservationStatus;
import com.jayaprakash.oms.inventory.repository.InventoryItemRepository;
import com.jayaprakash.oms.inventory.repository.ReservationRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies inventory-service end to end against an embedded Kafka broker:
 * an order event goes in, stock moves, and the right event comes out.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {
                Topics.ORDER_CREATED, Topics.ORDER_CANCELLED,
                Topics.INVENTORY_RESERVED, Topics.INVENTORY_FAILED,
                Topics.PAYMENT_COMPLETED
        })
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
class InventorySagaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private InventoryItemRepository itemRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    private Consumer<String, Object> testConsumer;

    /**
     * Resets stock and creates a consumer for the outbound topics.
     *
     * <p>Input: none. Processing: clears both tables, seeds a known stock level,
     * and subscribes a fresh consumer group to the two topics this service
     * publishes. Output: none.
     */
    @BeforeEach
    void setUp() {
        reservationRepository.deleteAll();
        itemRepository.deleteAll();
        itemRepository.save(new InventoryItem("LAPTOP-001", 10));

        Map<String, Object> props = KafkaTestUtils.consumerProps(
                "inv-test-" + System.nanoTime(), "true", embeddedKafka);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.jayaprakash.oms.common.events");

        testConsumer = new DefaultKafkaConsumerFactory<String, Object>(
                props, new StringDeserializer(), new JsonDeserializer<>()).createConsumer();

        embeddedKafka.consumeFromEmbeddedTopics(
                testConsumer, Topics.INVENTORY_RESERVED, Topics.INVENTORY_FAILED);
    }

    @AfterEach
    void tearDown() {
        if (testConsumer != null) {
            testConsumer.close();
        }
    }

    /**
     * Publishes an order event onto the ingest topic.
     *
     * @param orderId  the order id to use
     * @param quantity how many laptops to request
     */
    private void sendOrderCreated(Long orderId, int quantity) {
        kafkaTemplate.send(Topics.ORDER_CREATED, String.valueOf(orderId), new OrderCreatedEvent(
                orderId, "ORD-" + orderId, "cust-1",
                List.of(new OrderLineItemDto("LAPTOP-001", quantity, new BigDecimal("500.00"))),
                new BigDecimal("500.00").multiply(BigDecimal.valueOf(quantity))));
    }

    @Test
    @DisplayName("an order event reserves stock and publishes InventoryReservedEvent")
    void reservesStockAndPublishes() {
        sendOrderCreated(1L, 3);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            InventoryItem item = itemRepository.findById("LAPTOP-001").orElseThrow();
            assertThat(item.getAvailableQuantity()).isEqualTo(7);
            assertThat(item.getReservedQuantity()).isEqualTo(3);
        });

        ConsumerRecords<String, Object> records = KafkaTestUtils.getRecords(testConsumer, TIMEOUT);
        var reserved = records.records(Topics.INVENTORY_RESERVED).iterator();
        assertThat(reserved.hasNext()).isTrue();
        assertThat(reserved.next().value()).isInstanceOf(InventoryReservedEvent.class);
    }

    @Test
    @DisplayName("an oversized order publishes InventoryFailedEvent and leaves stock untouched")
    void refusesOversizedOrder() {
        sendOrderCreated(2L, 999);

        ConsumerRecords<String, Object> records = KafkaTestUtils.getRecords(testConsumer, TIMEOUT);
        var failed = records.records(Topics.INVENTORY_FAILED).iterator();
        assertThat(failed.hasNext()).isTrue();
        assertThat(failed.next().value()).isInstanceOf(InventoryFailedEvent.class);

        InventoryItem item = itemRepository.findById("LAPTOP-001").orElseThrow();
        assertThat(item.getAvailableQuantity()).isEqualTo(10);
        assertThat(item.getReservedQuantity()).isZero();
    }

    @Test
    @DisplayName("a cancellation event releases the held stock (the compensating step)")
    void cancellationReleasesStock() {
        sendOrderCreated(3L, 4);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(itemRepository.findById("LAPTOP-001").orElseThrow().getReservedQuantity())
                        .isEqualTo(4));

        // Order-service publishes this after a failed payment.
        kafkaTemplate.send(Topics.ORDER_CANCELLED, "3",
                new OrderCancelledEvent(3L, "Card declined"));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            InventoryItem item = itemRepository.findById("LAPTOP-001").orElseThrow();
            assertThat(item.getAvailableQuantity()).isEqualTo(10);
            assertThat(item.getReservedQuantity()).isZero();
        });
    }

    @Test
    @DisplayName("a completed payment commits the hold, so reserved stock drops back to zero")
    void paymentCompletionCommitsStock() {
        sendOrderCreated(5L, 3);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(itemRepository.findById("LAPTOP-001").orElseThrow().getReservedQuantity())
                        .isEqualTo(3));

        // Payment-service publishes this after a successful charge.
        kafkaTemplate.send(Topics.PAYMENT_COMPLETED, "5",
                new PaymentCompletedEvent(5L, "PAY-TEST", new BigDecimal("1500.00")));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            InventoryItem item = itemRepository.findById("LAPTOP-001").orElseThrow();
            assertThat(item.getAvailableQuantity()).isEqualTo(7);
            assertThat(item.getReservedQuantity()).isZero();
        });

        assertThat(reservationRepository.findByOrderId(5L))
                .allSatisfy(hold -> assertThat(hold.getStatus()).isEqualTo(ReservationStatus.COMMITTED));
    }

    @Test
    @DisplayName("a redelivered order event does not reserve the stock twice")
    void duplicateOrderEventReservesOnce() {
        sendOrderCreated(4L, 2);

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(itemRepository.findById("LAPTOP-001").orElseThrow().getReservedQuantity())
                        .isEqualTo(2));

        sendOrderCreated(4L, 2);

        await().during(Duration.ofSeconds(3)).atMost(TIMEOUT).untilAsserted(() -> {
            InventoryItem item = itemRepository.findById("LAPTOP-001").orElseThrow();
            assertThat(item.getReservedQuantity()).isEqualTo(2);
            assertThat(item.getAvailableQuantity()).isEqualTo(8);
        });
    }
}
