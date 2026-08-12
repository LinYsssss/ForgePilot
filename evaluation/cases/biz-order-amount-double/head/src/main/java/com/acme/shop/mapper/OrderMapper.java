package com.acme.shop.mapper;

import com.acme.shop.entity.Order;

/** 订单读写，实现由持久层框架生成。 */
public interface OrderMapper {

    Order selectById(Long id);

    /** 更新实付金额（分）。 */
    void updatePaidAmount(Long id, long paidFen);
}
