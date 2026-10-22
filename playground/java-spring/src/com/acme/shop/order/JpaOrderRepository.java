package com.acme.shop.order;

import org.springframework.stereotype.Repository;

@Repository
public class JpaOrderRepository implements OrderRepository {
    private int nextId = 1;

    @Override
    public Order save(Order order) {
        return new Order(nextId++, order.customer(), order.items());
    }
}
