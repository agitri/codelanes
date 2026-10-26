from fastapi import APIRouter

from shop.orders.memory import InMemoryOrderRepository
from shop.orders.models import Order
from shop.orders.service import OrderService

router = APIRouter(prefix="/orders", tags=["orders"])
service = OrderService(InMemoryOrderRepository())


@router.post("/")
async def create_order(customer: str, items: int) -> Order:
    return service.place(customer, items)


@router.get("/{order_id}")
async def get_order(order_id: int) -> Order | None:
    return service.find(order_id)
