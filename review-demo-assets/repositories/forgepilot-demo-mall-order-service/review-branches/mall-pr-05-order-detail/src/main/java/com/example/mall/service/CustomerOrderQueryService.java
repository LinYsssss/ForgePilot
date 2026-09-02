package com.example.mall.service;

import com.example.mall.entity.Order;
import com.example.mall.mapper.OrderMapper;

/** C 端订单详情。 */
public class CustomerOrderQueryService {
    private final OrderMapper orders;
    public CustomerOrderQueryService(OrderMapper orders) { this.orders = orders; }
    public Order detail(Long orderId, Long currentUserId) { return orders.selectById(orderId); }
}
