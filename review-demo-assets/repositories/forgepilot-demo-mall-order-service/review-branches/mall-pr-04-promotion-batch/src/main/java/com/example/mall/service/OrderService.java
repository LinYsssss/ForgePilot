package com.example.mall.service;

import com.example.mall.entity.Order;
import com.example.mall.mapper.OrderMapper;

public class OrderService {
    private final OrderMapper orderMapper = new OrderMapper();

    public void shipOrder(Long orderId) {
        Order order = orderMapper.findById(orderId);
        order.setStatus("SHIPPED");
    }

    public boolean isPaid(Long orderId) {
        Order order = orderMapper.findById(orderId);
        return order.getPayStatus().equals("PAID");
    }
}
