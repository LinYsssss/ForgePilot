package com.acme.shop.entity;

import java.time.Instant;

/**
 * 订单实体，对应 orders 表。
 *
 * <p>注意：`status` 与 `pay_status` 是两个独立字段，订单可能处于 WAIT_SHIP
 * 而 pay_status 仍为 UNPAID。参见 docs/order-flow.md。
 */
public class Order {

    private Long id;
    private Long userId;
    /** CREATED / PAID / WAIT_SHIP / SHIPPED / COMPLETED / CANCELLED */
    private String status;
    /** UNPAID / PAID / REFUNDED */
    private String payStatus;
    /** 应付金额（分） */
    private long amount;
    private String receiverAddress;
    private boolean addressRiskFlagged;
    private Instant shippedAt;
    private Instant deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPayStatus() {
        return payStatus;
    }

    public void setPayStatus(String payStatus) {
        this.payStatus = payStatus;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public String getReceiverAddress() {
        return receiverAddress;
    }

    public void setReceiverAddress(String receiverAddress) {
        this.receiverAddress = receiverAddress;
    }

    public boolean isAddressRiskFlagged() {
        return addressRiskFlagged;
    }

    public void setAddressRiskFlagged(boolean addressRiskFlagged) {
        this.addressRiskFlagged = addressRiskFlagged;
    }

    public Instant getShippedAt() {
        return shippedAt;
    }

    public void setShippedAt(Instant shippedAt) {
        this.shippedAt = shippedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
