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
}
