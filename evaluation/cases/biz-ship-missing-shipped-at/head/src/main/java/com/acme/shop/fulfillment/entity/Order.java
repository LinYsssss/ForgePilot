package com.acme.shop.fulfillment.entity;

import java.time.Instant;

/** 订单实体（发货相关字段节选）。 */
public class Order {

    private Long id;
    /** CREATED / PAID / WAIT_SHIP / SHIPPED / COMPLETED / CANCELLED */
    private String status;
    /** UNPAID / PAID / REFUNDED */
    private String payStatus;
    /** 发货时间：售后时效与超时自动确认收货的起点。 */
    private Instant shippedAt;
    private Instant deletedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
