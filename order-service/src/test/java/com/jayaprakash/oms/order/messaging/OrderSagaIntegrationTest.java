package com.jayaprakash.oms.order.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayaprakash.oms.common.events.InventoryFailedEvent;
import com.jayaprakash.oms.common.events.InventoryReservedEvent;
import com.jayaprakash.oms.common.events.OrderCancelledEvent;
import com.jayaprakash.oms.common.events.OrderCreatedEvent;
import com.jayaprakash.oms.common.events.PaymentCompletedEvent;
import com.jayaprakash.oms.common.events.PaymentFailedEvent;
import com.jayaprakash.oms.common.events.Topics;
import com.jayaprakash.oms.order.api.dto.OrderLineItemRequest;
import com.jayaprakash.oms.order.api.dto.OrderResponse;
import com.jayaprakash.oms.order.api.dto.PlaceOrderRequest;
import com.jayaprakash.oms.order.domain.OrderStatus;
import com.jayaprakash.oms.order.repository.OrderRepository;
import com.jayaprakash.oms.order.service.OrderService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
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
 * Verifies order-service against a real (embedded) Kafka broker.
 *
 * <p>The other two services are not running here, so this test plays their part:
 * it publishes the events they would have published and asserts that
 * order-service reacts correctly. That keeps the test fast and independent while
 * still exercising real serialisation, real listeners, and real transactions.
 *
 * <p>Nothing external is required — the broker runs in-process and the database
 * is in-memory H2.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {
                Topics.ORDER_CREATED, Topics.ORDER_CANCELLED,
                Topics.INVENTORY_RESERVED, Topics.INVENTORY_FAILED,
                Topics.PAYMENT_COMPLETED, Topics.PAYMENT_FAILED
        })
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
class OrderSagaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    /** Test-side consumer used to read what order-service published. */
    private Consumer<String, Object> testConsumer;

    /**
     * Creates a fresh consumer and clears the database before each test.
     *
     * <p>Input: none. Processing: builds a consumer subscribed to the two topics
     * order-service produces, and empties the orders table. Output: none. The
     * broker is shared across tests and each new consumer group reads from the
     * beginning, so it also sees earlier tests' messages: assertions must filter
     * by their own order id.
     */
    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();

        Map<String, Object> props = KafkaTestUtils.consumerProps(
                "test-consumer-" + System.nanoTime(), "true", embeddedKafka);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.jayaprakash.oms.common.events");

        testConsumer = new DefaultKafkaConsumerFactory<String, Object>(
                props, new StringDeserializer(), new JsonDeserializer<>()).createConsumer();

        embeddedKafka.consumeFromEmbeddedTopics(testConsumer, Topics.ORDER_CREATED, Topics.ORDER_CANCELLED);
    }

    /**
     * Closes the test consumer so the broker does not accumulate connections.
     */
    @AfterEach
    void tearDown() {
        if (testConsumer != null) {
            testConsumer.close();
        }
    }

    /**
     * Places a standard test order.
     *
     * <p>Input: the total price to use. Output: the created order, so tests can
     * take its id.
     *
     * @param price unit price of the single line item
     * @return the placed order
     */
    private OrderResponse placeOrder(String price) {
        return orderService.placeOrder(new PlaceOrderRequest("cust-1", List.of(
                new OrderLineItemRequest("LAPTOP-001", 1, new BigDecimal(price)))));
    }

    @Test
    @DisplayName("placing an order publishes OrderCreatedEvent to start the saga")
    void placingOrderPublishesCreatedEvent() {
        OrderResponse order = placeOrder("500.00");

        ConsumerRecords<String, Object> records = KafkaTestUtils.getRecords(testConsumer, TIMEOUT);
        List<OrderCreatedEvent> created = new ArrayList<>();
        for (var record : records.records(Topics.ORDER_CREATED)) {
            if (record.value() instanceof OrderCreatedEvent event && event.orderId().equals(order.id())) {
                created.add(event);
            }
        }

        assertThat(created).hasSize(1);
        assertThat(created.get(0).totalAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("the happy path drives the order all the way to CONFIRMED")
    void happyPathConfirmsOrder() {
        OrderResponse order = placeOrder("500.00");

        // Stand in for inventory-service.
        kafkaTemplate.send(Topics.INVENTORY_RESERVED, String.valueOf(order.id()),
                new InventoryReservedEvent(order.id(), 1L, "cust-1", new BigDecimal("500.00")));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.INVENTORY_RESERVED));

        // Stand in for payment-service.
        kafkaTemplate.send(Topics.PAYMENT_COMPLETED, String.valueOf(order.id()),
                new PaymentCompletedEvent(order.id(), "PAY-123", new BigDecimal("500.00")));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.CONFIRMED));
    }

    @Test
    @DisplayName("an inventory failure cancels the order and publishes no compensation")
    void inventoryFailureCancelsWithoutCompensation() {
        OrderResponse order = placeOrder("500.00");

        kafkaTemplate.send(Topics.INVENTORY_FAILED, String.valueOf(order.id()),
                new InventoryFailedEvent(order.id(), "Insufficient stock for LAPTOP-001"));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var stored = orderRepository.findById(order.id()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(stored.getFailureReason()).contains("Insufficient stock");
        });

        // Drain everything and confirm nothing for this order landed on the compensation topic.
        ConsumerRecords<String, Object> records = KafkaTestUtils.getRecords(testConsumer, Duration.ofSeconds(3));
        List<OrderCancelledEvent> compensations = new ArrayList<>();
        for (var record : records.records(Topics.ORDER_CANCELLED)) {
            if (record.value() instanceof OrderCancelledEvent cancelled && cancelled.orderId().equals(order.id())) {
                compensations.add(cancelled);
            }
        }
        assertThat(compensations).isEmpty();
    }

    @Test
    @DisplayName("a payment failure cancels the order AND publishes the compensating event")
    void paymentFailureTriggersCompensation() {
        OrderResponse order = placeOrder("500.00");

        kafkaTemplate.send(Topics.INVENTORY_RESERVED, String.valueOf(order.id()),
                new InventoryReservedEvent(order.id(), 1L, "cust-1", new BigDecimal("500.00")));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.INVENTORY_RESERVED));

        kafkaTemplate.send(Topics.PAYMENT_FAILED, String.valueOf(order.id()),
                new PaymentFailedEvent(order.id(), "Card declined"));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.CANCELLED));

        // The stock must be given back: check the compensating event was published.
        await().atMost(TIMEOUT).untilAsserted(() -> {
            ConsumerRecords<String, Object> records =
                    KafkaTestUtils.getRecords(testConsumer, Duration.ofSeconds(2));
            boolean sawCompensation = false;
            for (var record : records.records(Topics.ORDER_CANCELLED)) {
                if (record.value() instanceof OrderCancelledEvent cancelled
                        && cancelled.orderId().equals(order.id())) {
                    sawCompensation = true;
                }
            }
            assertThat(sawCompensation).isTrue();
        });
    }

    @Test
    @DisplayName("a redelivered payment confirmation does not disturb a settled order")
    void duplicateEventsAreIgnored() {
        OrderResponse order = placeOrder("500.00");

        kafkaTemplate.send(Topics.PAYMENT_COMPLETED, String.valueOf(order.id()),
                new PaymentCompletedEvent(order.id(), "PAY-123", new BigDecimal("500.00")));

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(orderRepository.findById(order.id()).orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.CONFIRMED));

        var afterFirst = orderRepository.findById(order.id()).orElseThrow().getUpdatedAt();

        // Kafka delivers at least once, so the same event can genuinely arrive twice.
        kafkaTemplate.send(Topics.PAYMENT_COMPLETED, String.valueOf(order.id()),
                new PaymentCompletedEvent(order.id(), "PAY-123", new BigDecimal("500.00")));

        await().during(Duration.ofSeconds(3)).atMost(TIMEOUT).untilAsserted(() -> {
            var stored = orderRepository.findById(order.id()).orElseThrow();
            assertThat(stored.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(stored.getUpdatedAt()).isEqualTo(afterFirst);
        });
    }
}
