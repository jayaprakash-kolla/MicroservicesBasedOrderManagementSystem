package com.jayaprakash.oms.payment.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayaprakash.oms.common.events.InventoryReservedEvent;
import com.jayaprakash.oms.common.events.PaymentCompletedEvent;
import com.jayaprakash.oms.common.events.PaymentFailedEvent;
import com.jayaprakash.oms.common.events.Topics;
import com.jayaprakash.oms.payment.domain.PaymentStatus;
import com.jayaprakash.oms.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Duration;
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
 * Verifies payment-service against an embedded Kafka broker.
 *
 * <p>The test profile sets {@code payment.decline-above-amount} to 1000, so a
 * charge of 500 succeeds and a charge of 50000 is declined — which is how the
 * two branches of the saga are exercised without any randomness.
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(
        partitions = 1,
        topics = {Topics.INVENTORY_RESERVED, Topics.PAYMENT_COMPLETED, Topics.PAYMENT_FAILED})
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
class PaymentSagaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    private Consumer<String, Object> testConsumer;

    /**
     * Clears payments and subscribes a fresh consumer to the outbound topics.
     */
    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();

        Map<String, Object> props = KafkaTestUtils.consumerProps(
                "pay-test-" + System.nanoTime(), "true", embeddedKafka);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.jayaprakash.oms.common.events");

        testConsumer = new DefaultKafkaConsumerFactory<String, Object>(
                props, new StringDeserializer(), new JsonDeserializer<>()).createConsumer();

        embeddedKafka.consumeFromEmbeddedTopics(
                testConsumer, Topics.PAYMENT_COMPLETED, Topics.PAYMENT_FAILED);
    }

    @AfterEach
    void tearDown() {
        if (testConsumer != null) {
            testConsumer.close();
        }
    }

    /**
     * Publishes the event that authorises a charge.
     *
     * @param orderId the order to charge for
     * @param amount  how much
     */
    private void sendInventoryReserved(Long orderId, String amount) {
        kafkaTemplate.send(Topics.INVENTORY_RESERVED, String.valueOf(orderId),
                new InventoryReservedEvent(orderId, 10L, "cust-1", new BigDecimal(amount)));
    }

    @Test
    @DisplayName("a charge under the limit is recorded COMPLETED and announced")
    void smallChargeSucceeds() {
        sendInventoryReserved(1L, "500.00");

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var payment = paymentRepository.findByOrderId(1L);
            assertThat(payment).isPresent();
            assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        });

        ConsumerRecords<String, Object> records = KafkaTestUtils.getRecords(testConsumer, TIMEOUT);
        var completed = records.records(Topics.PAYMENT_COMPLETED).iterator();
        assertThat(completed.hasNext()).isTrue();
        assertThat(completed.next().value()).isInstanceOf(PaymentCompletedEvent.class);
    }

    @Test
    @DisplayName("a charge over the limit is recorded DECLINED and starts the unwind")
    void largeChargeIsDeclined() {
        sendInventoryReserved(2L, "50000.00");

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var payment = paymentRepository.findByOrderId(2L);
            assertThat(payment).isPresent();
            assertThat(payment.get().getStatus()).isEqualTo(PaymentStatus.DECLINED);
            assertThat(payment.get().getFailureReason()).contains("declined");
        });

        ConsumerRecords<String, Object> records = KafkaTestUtils.getRecords(testConsumer, TIMEOUT);
        var failed = records.records(Topics.PAYMENT_FAILED).iterator();
        assertThat(failed.hasNext()).isTrue();
        assertThat(failed.next().value()).isInstanceOf(PaymentFailedEvent.class);
    }

    @Test
    @DisplayName("a redelivered event leaves exactly one payment row")
    void duplicateEventChargesOnce() {
        sendInventoryReserved(3L, "500.00");

        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(paymentRepository.findByOrderId(3L)).isPresent());

        sendInventoryReserved(3L, "500.00");

        await().during(Duration.ofSeconds(3)).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(paymentRepository.findAll()).hasSize(1));
    }
}
