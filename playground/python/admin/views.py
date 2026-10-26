from admin import bp
from shop.orders.api import service


@bp.get("/")
def dashboard() -> str:
    return "Shop admin"


@bp.route("/orders/<int:order_id>", methods=["GET"])
def show_order(order_id: int) -> str:
    order = service.find(order_id)
    return f"{order.customer}: {order.items} items" if order else "No such order"
