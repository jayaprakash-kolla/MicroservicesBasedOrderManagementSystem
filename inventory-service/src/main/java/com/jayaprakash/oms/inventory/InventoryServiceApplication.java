package com.jayaprakash.oms.inventory;

import com.jayaprakash.oms.common.config.KafkaMessagingConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Inventory service: holds stock, and gives it back when a saga unwinds.
 *
 * <p>It reacts to two events and publishes two. It has no idea payment-service
 * exists — it only knows that orders get created and sometimes cancelled.
 */
@SpringBootApplication
@Import(KafkaMessagingConfig.class)
public class InventoryServiceApplication {

    /**
     * Boots the inventory service on port 8082.
     *
     * @param args standard Spring Boot command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
