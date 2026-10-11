package com.jayaprakash.oms.monitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Saga monitor: watches every saga topic and streams what it sees to the browser.
 *
 * <p>It is a pure observer. It joins Kafka in its own consumer group
 * ({@code saga-monitor}), so it receives its own copy of every event without
 * taking anything away from order, inventory, or payment service. None of
 * those services knows it exists, and stopping it changes nothing about how
 * orders are processed — which is exactly the property a consumer group gives
 * you.
 *
 * <p>Unlike the business services it does not import {@code KafkaMessagingConfig}:
 * it never publishes, and it reads every message as a raw JSON string rather
 * than as a typed event, so a new event type cannot break it.
 */
@SpringBootApplication
@EnableScheduling
public class SagaMonitorApplication {

    /**
     * Boots the saga monitor on port 18084.
     *
     * @param args standard Spring Boot command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(SagaMonitorApplication.class, args);
    }
}
