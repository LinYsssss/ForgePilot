package com.acme.inventory;

import java.util.ArrayList;
import java.util.List;

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

    public BatchAdjustmentResult adjustBatch(List<StockAdjustment> adjustments) {
        if (adjustments == null || adjustments.isEmpty()) {
            return new BatchAdjustmentResult(0, List.of());
        }
        int applied = 0;
        List<Long> failedItemIds = new ArrayList<>();
        for (StockAdjustment adjustment : adjustments) {
            try {
                this.adjustStock(adjustment);
                applied++;
            } catch (IllegalStateException e) {
                failedItemIds.add(adjustment.itemId());
            }
        }
        return new BatchAdjustmentResult(applied, failedItemIds);
    }
}
