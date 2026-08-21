package com.acme.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

    private final StockItemRepository stockItems;
    private final StockAuditRepository stockAudit;

    public InventoryService(StockItemRepository stockItems, StockAuditRepository stockAudit) {
        this.stockItems = stockItems;
        this.stockAudit = stockAudit;
    }

    @Transactional
    public void adjustStock(StockAdjustment adjustment) {
        int current = stockItems.currentQuantity(adjustment.itemId());
        int updated = current + adjustment.quantityDelta();
        if (updated < 0) {
            throw new IllegalStateException("stock for item " + adjustment.itemId() + " would become negative");
        }
        stockItems.updateQuantity(adjustment.itemId(), updated);
        stockAudit.append(adjustment.itemId(), adjustment.quantityDelta(), adjustment.reason());
    }
}
