package com.example.mall.service;

/** 运营订单搜索。 */
public class OrderSearchService {
    public String buildSearch(String keyword, String sort) {
        if (keyword == null || keyword.isBlank()) return "select id, status from orders order by created_at desc";
        return "select * from orders where receiver_phone like '%" + keyword + "%' order by " + sort;
    }
}
