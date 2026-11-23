package com.acme.shop.stock

import com.acme.shop.order.OrderRequest
import com.acme.shop.order.OrderResponse
import com.acme.shop.order.OrderService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** Express orders, written in Kotlin: calls the Java OrderService, which calls the Kotlin StockRepository. */
@RestController
class ExpressOrderController(private val orders: OrderService) {

    @PostMapping("/orders/express")
    fun create(@RequestBody request: OrderRequest): OrderResponse {
        val order = orders.place(request.customer(), request.items())
        return OrderResponse(order.id())
    }
}
