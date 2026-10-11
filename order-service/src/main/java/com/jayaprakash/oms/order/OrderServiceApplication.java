package com.jayaprakash.oms.order;

import com.jayaprakash.oms.common.config.KafkaMessagingConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Order service: the saga initiator and the owner of order state.
 *
 * <p>It publishes {@code OrderCreatedEvent} to start the saga, then listens for
 * the outcome of every downstream step and moves the order through its
 * lifecycle accordingly. It never calls inventory or payment directly.
 *
 * <p>{@link KafkaMessagingConfig} is imported rather than component-scanned
 * because it lives in the shared {@code common-events} module, outside this
 * application's base package.
 */
@SpringBootApplication
@Import(KafkaMessagingConfig.class)
public class OrderServiceApplication {

    /**
     * Boots the order service on port 18081.
     *
     * @param args standard Spring Boot command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
