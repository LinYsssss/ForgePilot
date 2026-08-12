package com.acme.inventory;

public interface StockItemRepository {

    int currentQuantity(long itemId);

    void updateQuantity(long itemId, int newQuantity);
}
