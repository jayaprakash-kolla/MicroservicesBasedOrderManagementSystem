package com.jayaprakash.oms.inventory.repository;

import com.jayaprakash.oms.inventory.domain.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Database access for stock levels, keyed by SKU code.
 */
public interface InventoryItemRepository extends JpaRepository<InventoryItem, String> {
}
