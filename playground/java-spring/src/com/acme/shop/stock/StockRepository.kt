package com.acme.shop.stock

import org.springframework.stereotype.Repository

/** Keeps track of what is in the warehouse. Written in Kotlin; the Java OrderService calls it. */
@Repository
class StockRepository {
    private val onHand = mutableMapOf("widget" to 100)

    fun reserve(items: Int): Boolean {
        val left = available() - items
        if (left < 0) return false
        onHand["widget"] = left
        return true
    }

    fun available(): Int = onHand.getOrDefault("widget", 0)
}
