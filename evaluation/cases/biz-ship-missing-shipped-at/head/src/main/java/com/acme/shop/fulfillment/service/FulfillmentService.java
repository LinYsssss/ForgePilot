package com.acme.shop.fulfillment.service;

import com.acme.shop.fulfillment.entity.Order;
import com.acme.shop.fulfillment.mapper.OperationLogMapper;
import com.acme.shop.fulfillment.mapper.OrderMapper;

/**
 * 发货履约服务。
 *
 * <p>本次改造：发货改为数据库条件更新，防止并发重复发货
 * （docs/order-flow.md 要求发货接口防重复提交）。
 */
public class FulfillmentService {

    private final OrderMapper orderMapper;
    private final OperationLogMapper operationLogMapper;

    public FulfillmentService(OrderMapper orderMapper, OperationLogMapper operationLogMapper) {
        this.orderMapper = orderMapper;
        this.operationLogMapper = operationLogMapper;
    }

    public void ship(Long orderId, Long operatorId) {
        Order order = orderMapper.selectById(orderId);
        validate(order);
        // 条件更新：仅 WAIT_SHIP → SHIPPED 才生效，并发重复请求只有一次成功。
        int updated = orderMapper.compareAndSetStatus(orderId, "WAIT_SHIP", "SHIPPED");
        if (updated == 0) {
            throw new IllegalStateException("订单已被并发处理，发货未执行");
        }
        operationLogMapper.insert(orderId, operatorId, "SHIP");
    }

    private void validate(Order order) {
        if (order == null || order.getDeletedAt() != null) {
            throw new IllegalStateException("订单不存在或已删除");
        }
        if (!"PAID".equals(order.getPayStatus())) {
            throw new IllegalStateException("支付未完成，禁止发货");
        }
        if (!"WAIT_SHIP".equals(order.getStatus())) {
            throw new IllegalStateException("订单状态不可发货");
        }
    }
}
