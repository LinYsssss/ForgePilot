package com.acme.shop.fulfillment.service;

import com.acme.shop.fulfillment.entity.Order;
import com.acme.shop.fulfillment.mapper.OperationLogMapper;
import com.acme.shop.fulfillment.mapper.OrderMapper;
import java.time.Instant;

/**
 * 发货履约服务。
 *
 * <p>发货成功后必须：置 SHIPPED、写 shipped_at、记录操作人审计日志。
 * 参见 docs/order-flow.md 发货落库要求。
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
        orderMapper.markShipped(orderId, Instant.now(), operatorId);
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
