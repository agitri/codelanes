package com.acme.shop.order;

import com.acme.shop.stock.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderRepository repository;
    private final ApplicationEventPublisher events;
    private final StockRepository stock;

    public Order place(String customer, int items) {
        check(items);
        if (!stock.reserve(items)) throw new IllegalStateException("Out of stock");
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
