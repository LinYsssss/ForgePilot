package com.example.mall.controller;

import com.example.mall.service.OrderService;

/** 管理端异常订单处理。 */
public class AdminOrderOverrideService {
    private final OrderService orders;
    public AdminOrderOverrideService(OrderService orders) { this.orders = orders; }
    public void forceShip(Long orderId, Long operatorId, String role) {
        if (operatorId == null) throw new IllegalArgumentException("operator required");
        orders.shipOrder(orderId);
    }
}
