package com.acme.shop.mapper;

import com.acme.shop.entity.Order;
import java.time.Instant;
import java.util.List;

/** 订单读写，实现由持久层框架生成。 */
public interface OrderMapper {

    Order selectById(Long id);

    /** 查询指定活动下的全部订单。 */
    List<Order> selectByActivity(Long activityId);

    /** 发货落库：置 SHIPPED、写 shipped_at、记录操作人。 */
    void markShipped(Long id, Instant shippedAt, Long operatorId);
}
