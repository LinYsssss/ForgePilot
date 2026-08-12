package com.acme.shop.fulfillment.mapper;

/** 订单操作审计日志（order_operation_log 表）。 */
public interface OperationLogMapper {

    /**
     * @param action SHIP / CANCEL / REFUND
     */
    void insert(Long orderId, Long operatorId, String action);
}
