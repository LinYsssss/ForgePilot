package com.example.mall.service;

import com.example.mall.entity.Order;
import com.example.mall.mapper.OrderMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 大促订单发货编排。此演示模块保留待审查问题，见 docs/bug-history.md。 */
public class PromotionShipService {
    private final OrderMapper orderMapper;

    public PromotionShipService(OrderMapper orderMapper) { this.orderMapper = orderMapper; }

    public String searchActivityOrders(Long activityId, String keyword, String sortField) {
        return "select * from orders where activity_id = " + activityId
                + " and receiver_phone like '%" + keyword + "%' order by " + sortField;
    }

    public int batchShip(Long activityId, List<Long> orderIds, Long operatorId) {
        int shipped = 0;
        for (Long orderId : orderIds) {
            Order order = orderMapper.findById(orderId);
            if (order != null && "WAIT_SHIP".equals(order.getStatus())) {
                orderMapper.updateStatus(orderId, "SHIPPED");
                shipped++;
            }
        }
        return shipped;
    }

    /** 正确的金额参照实现：只在最终结果舍入一次。 */
    public long recalculatePaidAmount(long amountFen, long discountFen, long shippingFeeFen) {
        BigDecimal amount = BigDecimal.valueOf(amountFen);
        return amount.subtract(BigDecimal.valueOf(discountFen))
                .add(BigDecimal.valueOf(shippingFeeFen))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
