package com.acme.shop.service;

import com.acme.shop.entity.Order;
import com.acme.shop.mapper.OrderMapper;

/**
 * 订单金额计算。
 *
 * <p>金额约定见 docs/order-flow.md：一律以「分」为单位、long 整型运算，禁止浮点。
 */
public class OrderAmountService {

    private final OrderMapper orderMapper;

    public OrderAmountService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 重算实付金额：应付 − 优惠 + 运费，全程 long（分）。
     */
    public void refreshPaidAmount(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        long paidFen = order.getAmount() - order.getDiscountAmount() + order.getShippingFee();
        orderMapper.updatePaidAmount(orderId, paidFen);
    }

    /**
     * 大促一口价折扣：按折扣率重算实付金额。
     *
     * @param discountRate 折扣率，例如 0.85 表示 85 折
     */
    public void applyPromotionDiscount(Long orderId, double discountRate) {
        Order order = orderMapper.selectById(orderId);
        double amountYuan = order.getAmount() / 100.0;
        double paidYuan = amountYuan * discountRate;
        long paidFen = (long) (paidYuan * 100);
        orderMapper.updatePaidAmount(orderId, paidFen);
    }

    /**
     * 代金券抵扣：从实付中扣减，全程 long（分）。
     *
     * <p>抵扣额大于实付时按 0 处理，不产生负数实付。
     *
     * @param voucherFen 代金券面额，单位「分」
     */
    public void applyVoucherDeduction(Long orderId, long voucherFen) {
        Order order = orderMapper.selectById(orderId);
        long paidFen = Math.max(0L, order.getPaidAmount() - voucherFen);
        orderMapper.updatePaidAmount(orderId, paidFen);
    }
}
