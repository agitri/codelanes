from abc import ABC, abstractmethod

from shop.orders.models import Order


class OrderRepository(ABC):
    """Where orders are kept. The service only knows this interface."""

    @abstractmethod
    def save(self, order: Order) -> Order:
        ...

    @abstractmethod
    def find(self, order_id: int) -> Order | None:
        ...
