package com.example.mall.controller;

import com.example.mall.entity.Order;
import com.example.mall.service.PromotionShipService;
import java.util.List;

/**
 * 大促活动运营接口。
 */
public class PromotionController {

    private final PromotionShipService promotionShipService;

    public PromotionController(PromotionShipService promotionShipService) {
        this.promotionShipService = promotionShipService;
    }

    /** 一键批量发货。 */
    public String batchShip(Long activityId, Long currentUserId) {
        int count = promotionShipService.batchShip(activityId, currentUserId);
        return "shipped=" + count;
    }

    /** 订单详情，活动页复用。 */
    public Order orderDetail(Long orderId) {
        return promotionShipService.searchActivityOrders(null, "", "id").stream()
                .filter(o -> o.getId().equals(orderId))
                .findFirst()
                .orElse(null);
    }

    /** 活动订单搜索。 */
    public List<Order> search(Long activityId, String keyword, String sortField) {
        return promotionShipService.searchActivityOrders(activityId, keyword, sortField);
    }
}
