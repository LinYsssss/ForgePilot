package com.acme.inventory;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping("/api/stock/adjustments")
    public void adjust(@RequestBody StockAdjustment adjustment) {
        inventoryService.adjustStock(adjustment);
    }

    @PostMapping("/api/stock/adjustments/batch")
    public BatchAdjustmentResult adjustBatch(@RequestBody List<StockAdjustment> adjustments) {
        return inventoryService.adjustBatch(adjustments);
    }
}
