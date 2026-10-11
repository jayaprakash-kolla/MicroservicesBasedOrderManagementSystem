package com.jayaprakash.oms.payment;

import com.jayaprakash.oms.common.config.KafkaMessagingConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Payment service: the last step of the saga, and the one most likely to fail.
 *
 * <p>It listens for {@code InventoryReservedEvent} — never for
 * {@code OrderCreatedEvent} — because charging a customer for goods that turned
 * out to be unavailable would mean issuing refunds instead of simply declining.
 * The ordering of saga steps is a design decision, not an accident.
 */
@SpringBootApplication
@Import(KafkaMessagingConfig.class)
public class PaymentServiceApplication {

    /**
     * Boots the payment service on port 18083.
     *
     * @param args standard Spring Boot command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
