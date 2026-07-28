package com.example.mall.mapper;

import com.example.mall.entity.Order;

public class OrderMapper {
    public Order findById(Long id) {
        return new Order();
    }

    public String searchByKeyword(String keyword) {
        return "select * from orders where username like '%" + keyword + "%'";
    }
}
