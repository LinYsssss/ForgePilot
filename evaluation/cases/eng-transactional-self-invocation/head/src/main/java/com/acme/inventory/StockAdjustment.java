package com.acme.inventory;

public record StockAdjustment(long itemId, int quantityDelta, String reason) {
}
