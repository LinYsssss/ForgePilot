package com.example.mallorder;

public class OrderService {
    public void ship(Order order) {
        // Demo bug: shipping should check paid status first.
        order.markShipped();
    }

    public String findByUserInput(String userInput) {
        // Demo bug: SQL concatenation risk.
        return "select * from orders where user_id = '" + userInput + "'";
    }
}
