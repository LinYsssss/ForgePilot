package com.acme.shop.entity;

/**
 * 订单实体（金额相关字段节选）。
 *
 * <p>所有金额字段单位为「分」，bigint 整型存储，禁止浮点类型。参见 docs/order-flow.md 金额约定。
 */
public class Order {

    private Long id;
    private Long userId;
    /** 应付金额（分） */
    private long amount;
    /** 优惠抵扣（分） */
    private long discountAmount;
    /** 运费（分） */
    private long shippingFee;
    /** 实付金额（分） */
    private long paidAmount;

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

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public long getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(long discountAmount) {
        this.discountAmount = discountAmount;
    }

    public long getShippingFee() {
        return shippingFee;
    }

    public void setShippingFee(long shippingFee) {
        this.shippingFee = shippingFee;
    }

    public long getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(long paidAmount) {
        this.paidAmount = paidAmount;
    }
}
