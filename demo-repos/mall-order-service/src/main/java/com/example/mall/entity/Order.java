package com.example.mall.entity;

/** 订单实体，字段与 docs/db-schema.md 的 orders 表对应。金额单位为分。 */
public class Order {
    private Long id;
    private Long userId;
    private String status;
    private String payStatus;
    private long amount;
    private long paidAmount;
    private String receiverPhone;
    private String receiverAddress;
    private String shippedAt;

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getStatus() {
        return status;
    }

    public String getPayStatus() {
        return payStatus;
    }

    public long getAmount() {
        return amount;
    }

    public long getPaidAmount() {
        return paidAmount;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public String getReceiverAddress() {
        return receiverAddress;
    }

    public String getShippedAt() {
        return shippedAt;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setPaidAmount(long paidAmount) {
        this.paidAmount = paidAmount;
    }

    public void setShippedAt(String shippedAt) {
        this.shippedAt = shippedAt;
    }
}
