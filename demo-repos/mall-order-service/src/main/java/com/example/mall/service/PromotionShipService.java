package com.example.mall.service;

import com.example.mall.entity.Order;
import com.example.mall.mapper.OrderMapper;
import java.util.List;

/**
 * 大促批量发货，运营在活动期间一键处理积压订单。
 */
public class PromotionShipService {

    private final OrderMapper orderMapper;

    public PromotionShipService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    /**
     * 批量发货：把指定活动下所有待发货订单一次性发出。
     */
    public int batchShip(Long activityId, Long operatorId) {
        List<Order> orders = orderMapper.selectByActivity(activityId);
        int shipped = 0;
        for (Order order : orders) {
            if ("WAIT_SHIP".equals(order.getStatus())) {
                order.setStatus("SHIPPED");
                orderMapper.updateStatus(order.getId(), "SHIPPED");
                shipped++;
            }
        }
        return shipped;
    }

    /**
     * 活动价重算：按折扣率重新计算实付金额。
     *
     * @param discountRate 折扣率，例如 0.85 表示 85 折
     */
    public void recalculatePaidAmount(Long orderId, double discountRate) {
        Order order = orderMapper.selectById(orderId);
        double amountYuan = order.getAmount() / 100.0;
        double paidYuan = amountYuan * discountRate;
        long paidFen = (long) (paidYuan * 100);
        orderMapper.updatePaidAmount(orderId, paidFen);
    }

    /**
     * 活动订单查询，供运营后台按条件筛选。
     */
    public List<Order> searchActivityOrders(Long activityId, String keyword, String sortField) {
        String sql = "select * from orders where activity_id = " + activityId
                + " and receiver_address like '%" + keyword + "%'"
                + " order by " + sortField;
        return orderMapper.selectBySql(sql);
    }
}
