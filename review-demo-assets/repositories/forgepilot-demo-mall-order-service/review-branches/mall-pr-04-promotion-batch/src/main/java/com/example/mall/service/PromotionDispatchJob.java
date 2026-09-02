package com.example.mall.service;

import com.example.mall.mapper.OrderMapper;
import java.util.List;

/** 大促批量发货任务。 */
public class PromotionDispatchJob {
    private final OrderMapper orders;
    public PromotionDispatchJob(OrderMapper orders) { this.orders = orders; }
    public int dispatch(List<Long> orderIds, Long operatorId) {
        for (Long id : orderIds) orders.updateStatus(id, "SHIPPED");
        return orderIds.size();
    }
}
