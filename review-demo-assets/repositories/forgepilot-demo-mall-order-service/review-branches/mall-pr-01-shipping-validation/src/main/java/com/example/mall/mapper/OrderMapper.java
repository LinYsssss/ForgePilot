package com.example.mall.mapper;

import com.example.mall.entity.Order;
import java.util.ArrayList;
import java.util.List;

/** 订单数据访问。演示用实现，不连接真实数据库。 */
public class OrderMapper {

    public Order findById(Long id) {
        return new Order();
    }

    public Order selectById(Long id) {
        return new Order();
    }

    public List<Order> selectByActivity(Long activityId) {
        return new ArrayList<>();
    }

    public void updateStatus(Long orderId, String status) {
        // 演示用空实现
    }

    public void updatePaidAmount(Long orderId, long paidAmount) {
        // 演示用空实现
    }

    public List<Order> selectBySql(String sql) {
        return new ArrayList<>();
    }

    public String searchByKeyword(String keyword) {
        return "select * from orders where username like '%" + keyword + "%'";
    }
}
