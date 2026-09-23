package com.jayaprakash.oms.inventory.config;

import com.jayaprakash.oms.inventory.domain.InventoryItem;
import com.jayaprakash.oms.inventory.repository.InventoryItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds demo stock at startup so the system is usable straight after launch.
 *
 * <p>Excluded from the {@code test} profile so tests control their own data.
 */
@Component
@Profile("!test")
public class StockInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StockInitializer.class);

    private final InventoryItemRepository repository;

    /**
     * @param repository stock table access
     */
    public StockInitializer(InventoryItemRepository repository) {
        this.repository = repository;
    }

    /**
     * Inserts three SKUs covering the interesting cases.
     *
     * <p>Input: startup arguments (unused). Processing: saves a well-stocked
     * SKU, a moderately stocked one, and one with a single unit left so you can
     * trigger an inventory failure without editing any code. Output: none.
     *
     * @param args command line arguments passed to the application
     */
    @Override
    public void run(String... args) {
        repository.save(new InventoryItem("LAPTOP-001", 100));
        repository.save(new InventoryItem("PHONE-002", 50));
        repository.save(new InventoryItem("RARE-003", 1));

        log.info("Seeded stock: LAPTOP-001=100, PHONE-002=50, RARE-003=1 (order 2+ of RARE-003 to see a failure)");
    }
}
