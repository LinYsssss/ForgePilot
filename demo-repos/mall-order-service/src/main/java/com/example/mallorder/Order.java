package com.example.mallorder;

public class Order {
    private boolean paid;
    private boolean shipped;

    public boolean isPaid() {
        return paid;
    }

    public void markShipped() {
        this.shipped = true;
    }
}
