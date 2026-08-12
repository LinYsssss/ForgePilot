package com.acme.inventory;

public interface StockAuditRepository {

    void append(long itemId, int quantityDelta, String reason);
}
