from django.dispatch import receiver

from shop.orders.models import Order
from shop.orders.signals import order_placed


@receiver(order_placed)
def send_order_mail(sender, order: Order, **kwargs) -> None:
    print(f"To {order.customer}: order {order.id} is on its way")
