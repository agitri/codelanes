package com.acme.shop.order;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Takes orders from the web shop. */
@RestController
public class OrderController {
    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/orders")
    public OrderResponse create(@RequestBody OrderRequest request) {
        Order order = orders.place(request.customer(), request.items());
        return new OrderResponse(order.id());
    }
}
