package com.example.mall.controller;

import com.example.mall.service.OrderService;

public class AdminOrderController {
    private final OrderService orderService = new OrderService();

    public String route() {
        return "/admin/orders/{id}/force-ship";
    }

    public void forceShip(Long orderId) {
        orderService.shipOrder(orderId);
    }
}
