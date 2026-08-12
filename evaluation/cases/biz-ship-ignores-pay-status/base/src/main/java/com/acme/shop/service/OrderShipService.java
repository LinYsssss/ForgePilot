package com.acme.shop.service;

import com.acme.shop.entity.Order;
import com.acme.shop.mapper.OrderMapper;
import java.time.Instant;

/**
 * 发货服务。
 *
 * <p>发货前置校验见 docs/order-flow.md 发货规则：订单存在未删除、
 * pay_status = PAID、status = WAIT_SHIP、收货地址可用，四项必须同时满足。
 */
public class OrderShipService {

    private final OrderMapper orderMapper;

    public OrderShipService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /** 单笔发货。 */
    public void ship(Long orderId, Long operatorId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null || order.getDeletedAt() != null) {
            throw new IllegalStateException("订单不存在或已删除");
        }
        if (!"PAID".equals(order.getPayStatus())) {
            throw new IllegalStateException("支付未完成，禁止发货");
        }
        if (!"WAIT_SHIP".equals(order.getStatus())) {
            throw new IllegalStateException("订单状态不可发货");
        }
        doShip(order, operatorId);
    }

    /** 地址校验 + 发货落库（置 SHIPPED、写 shipped_at、记操作人）。 */
    private void doShip(Order order, Long operatorId) {
        if (order.getReceiverAddress() == null || order.isAddressRiskFlagged()) {
            throw new IllegalStateException("收货地址缺失或被风控标记");
        }
        orderMapper.markShipped(order.getId(), Instant.now(), operatorId);
    }
}
