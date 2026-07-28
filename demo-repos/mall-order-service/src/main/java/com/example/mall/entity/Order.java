package com.example.mall.entity;

public class Order {
    private Long id;
    private String status;
    private String payStatus;

    public Long getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }

    public String getPayStatus() {
        return payStatus;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
