package com.jayaprakash.oms.inventory.api;

import com.jayaprakash.oms.inventory.api.dto.AddStockRequest;
import com.jayaprakash.oms.inventory.api.dto.StockResponse;
import com.jayaprakash.oms.inventory.service.InventoryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read and top up stock levels.
 *
 * <p>Mostly an observation window: hitting {@code GET} before and after placing
 * an order is the clearest way to watch a reservation happen and, if payment
 * fails, watch it be released again.
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    /**
     * @param inventoryService the stock service
     */
    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * Lists every SKU with its available and reserved counts.
     *
     * <p>Input: none. Output: all stock rows.
     *
     * @return current stock levels
     */
    @GetMapping
    public List<StockResponse> listStock() {
        return inventoryService.listStock().stream().map(StockResponse::from).toList();
    }

    /**
     * Adds stock for a SKU, creating it if new.
     *
     * <p>Input: a validated {@link AddStockRequest}. Processing: increments
     * available stock. Output: the updated row.
     *
     * @param request what to restock and by how much
     * @return the updated stock level
     */
    @PostMapping
    public StockResponse addStock(@Valid @RequestBody AddStockRequest request) {
        return StockResponse.from(inventoryService.addStock(request.skuCode(), request.quantity()));
    }
}
