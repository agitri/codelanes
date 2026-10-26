from shop.orders.models import Order
from shop.orders.repository import OrderRepository
from shop.orders.signals import order_placed


class OrderService:
    """Places orders and tells the rest of the shop about them."""

    def __init__(self, repository: OrderRepository, max_items: int = 50):
        self.repository = repository
        self.max_items = max_items

    def place(self, customer: str, items: int) -> Order:
        self._check(items)
        order = self.repository.save(Order(0, customer, items))
        order_placed.send(sender=OrderService, order=order)
        return order

    def find(self, order_id: int) -> Order | None:
        return self.repository.find(order_id)

    def _check(self, items: int) -> None:
        if not 0 < items <= self.max_items:
            raise ValueError(f"An order needs 1 to {self.max_items} items")
