package com.acme.inventory;

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
}
