package com.example.mall.service;

import com.example.mall.entity.Order;
import com.example.mall.mapper.OrderMapper;

/** 仓库发货入口。 */
public class WarehouseShipmentService {
    private final OrderMapper orders;
    public WarehouseShipmentService(OrderMapper orders) { this.orders = orders; }
    public boolean ship(Long orderId, Long operatorId) {
        Order order = orders.findById(orderId);
        if (order == null || !"WAIT_SHIP".equals(order.getStatus())) return false;
        orders.updateStatus(orderId, "SHIPPED");
        return true;
    }
}
