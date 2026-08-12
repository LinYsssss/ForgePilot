package com.acme.shop.fulfillment.mapper;

import com.acme.shop.fulfillment.entity.Order;
import java.time.Instant;

/** 订单读写，实现由持久层框架生成。 */
public interface OrderMapper {

    Order selectById(Long id);

    /**
     * 发货落库：置 status = SHIPPED、写 shipped_at、记录操作人，单条 update 完成。
     */
    void markShipped(Long id, Instant shippedAt, Long operatorId);

    /**
     * 条件更新订单状态：update orders set status = #{next} where id = #{id} and status = #{expect}。
     *
     * @return 受影响行数，0 表示状态已被并发修改
     */
    int compareAndSetStatus(Long id, String expect, String next);
}
