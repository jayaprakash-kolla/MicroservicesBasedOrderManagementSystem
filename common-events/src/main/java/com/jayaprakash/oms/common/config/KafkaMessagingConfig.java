package com.jayaprakash.oms.common.config;

import com.jayaprakash.oms.common.events.Topics;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka wiring shared by all three business services.
 *
 * <p>Each service imports this with {@code @Import(KafkaMessagingConfig.class)}
 * rather than component-scanning it, because the services live in different
 * base packages. Keeping it here means the retry policy and serialisation rules
 * are defined once instead of copy-pasted three times.
 *
 * <p>Consumer-side deserialisation is configured in each service's
 * {@code application.yml}, since the consumer group id differs per service.
 */
@Configuration
public class KafkaMessagingConfig {

    /** How many times a failing message is retried before it is dead-lettered. */
    private static final long MAX_RETRY_ATTEMPTS = 2L;

    /** Pause between retry attempts, in milliseconds. */
    private static final long RETRY_INTERVAL_MS = 1000L;

    private final String bootstrapServers;

    /**
     * @param bootstrapServers broker list, read from
     *                         {@code spring.kafka.bootstrap-servers}; tests
     *                         override it with the embedded broker's address
     */
    public KafkaMessagingConfig(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * Builds the factory that creates Kafka producers.
     *
     * <p>Input: none (uses the injected broker list). Processing: configures
     * String keys and JSON values, and leaves Java type headers switched ON so
     * a consumer can tell an {@code InventoryReservedEvent} from an
     * {@code InventoryFailedEvent} without extra configuration. Output: the
     * producer factory.
     *
     * @return a configured producer factory
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // Wait for all in-sync replicas: losing a saga event would strand an order.
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * Exposes the template every publisher uses.
     *
     * <p>Declaring this bean makes Spring Boot's auto-configured template back
     * off, so there is exactly one {@code KafkaTemplate} in the context and the
     * generic types are unambiguous at every injection point.
     *
     * @param producerFactory the factory built above
     * @return the shared publishing template
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Defines what happens when a listener method throws.
     *
     * <p>Input: the publishing template. Processing: retries the message twice
     * with a one-second gap; if it still fails, republishes it to
     * {@code <topic>.DLT} so the partition is not blocked forever. Spring Boot
     * detects this bean and attaches it to every listener container
     * automatically. Output: the error handler.
     *
     * <p>{@link IllegalArgumentException} is marked non-retryable: a malformed
     * event will still be malformed on the third attempt, so retrying only
     * wastes time.
     *
     * @param template used to publish the dead letter
     * @return the configured error handler
     */
    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {

        // Always target partition 0: dead-letter topics are auto-created with a
        // single partition, so copying the source partition number would fail.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                (record, exception) -> new TopicPartition(record.topic() + Topics.DLT_SUFFIX, 0));

        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRY_ATTEMPTS));

        handler.addNotRetryableExceptions(IllegalArgumentException.class);

        return handler;
    }
}
