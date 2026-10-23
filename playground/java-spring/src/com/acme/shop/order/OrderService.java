package com.acme.shop.order;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository repository;
    private final ApplicationEventPublisher events;

    public Order place(String customer, int items) {
        check(items);
        Order order = repository.save(new Order(0, customer, items));
        events.publishEvent(new OrderPlaced(order));
        return order;
    }

    public Order place(Order order) {
        return place(order.customer(), order.items());
    }

    private void check(int items) {
        if (items <= 0) throw new IllegalArgumentException("An order needs items");
    }
}
