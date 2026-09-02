package com.example.mall.controller;

import com.example.mall.entity.Order;
import com.example.mall.mapper.OrderMapper;
import com.example.mall.service.PromotionShipService;
import java.util.List;

/** 运营大促入口；当前实现用于安全审查演示。 */
public class PromotionController {
    private final OrderMapper orderMapper;
    private final PromotionShipService promotionShipService;

    public PromotionController(OrderMapper orderMapper, PromotionShipService promotionShipService) {
        this.orderMapper = orderMapper;
        this.promotionShipService = promotionShipService;
    }

    public Order orderDetail(Long activityId, Long orderId, Long currentUserId) {
        List<Order> orders = orderMapper.selectByActivity(activityId);
        return orders.stream().filter(order -> orderId.equals(order.getId())).findFirst().orElse(null);
    }

    public int batchShip(Long activityId, List<Long> orderIds, Long operatorId) {
        return promotionShipService.batchShip(activityId, orderIds, operatorId);
    }
}
