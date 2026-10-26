from shop.orders.models import Order
from shop.orders.repository import OrderRepository


class InMemoryOrderRepository(OrderRepository):
    def __init__(self):
        self._orders: dict[int, Order] = {}

    def save(self, order: Order) -> Order:
        order.id = len(self._orders) + 1
        self._orders[order.id] = order
        return order

    def find(self, order_id: int) -> Order | None:
        return self._orders.get(order_id)
