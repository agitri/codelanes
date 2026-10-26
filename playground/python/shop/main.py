from fastapi import FastAPI

import shop.mail  # noqa: F401 (connects the order mail to the order_placed signal)
from shop.orders.api import router as orders_router

app = FastAPI()
app.include_router(orders_router)


@app.get("/health")
def health() -> dict:
    return {"ok": True}
