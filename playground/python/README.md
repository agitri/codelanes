# Python playground

A small shop to try CodeLanes in PyCharm: a FastAPI app (`shop/`), a Flask blueprint (`admin/`) and a Django app
(`mysite/`, `blog/`). The frameworks are **stubs**: `stubs/fastapi`, `stubs/flask` and `stubs/django` hold just the
few names the code uses, so it opens without installing anything (and runs: `PYTHONPATH=.:stubs python3 -c "import
shop.main"`). They are not the real packages.

**File → Open…** this folder (`playground/python`, not `playground`), then right-click `stubs` → **Mark Directory
as → Sources Root**, so that `fastapi`, `flask` and `django` resolve to the stubs (without it their imports are red,
and `PostDetail` shows no `View` parent). No interpreter is needed for the canvas, but with one configured PyCharm
infers more types (the standard library's too), so more lines show.

Try:
- **Follow a Request…** lists:

  ```
  GET /admin → views::dashboard
  GET /admin/orders/<int:order_id> → views::show_order
  ANY /blog/ → views::post_list
  GET /blog/posts/<int:pk>/ → views::PostDetail.get
  POST /blog/posts/<int:pk>/ → views::PostDetail.post
  GET /health → main::health
  POST /orders → api::create_order
  GET /orders/{order_id} → api::get_order
  ```

  The `/orders` routes get the `APIRouter(prefix="/orders")` prefix, the admin routes the blueprint's `url_prefix`
  (the blueprint is imported from `admin/__init__.py`), and the blog routes come through `include("blog.urls")`.
- Open `shop/orders/service.py`: the module and `OrderService` in the middle, `OrderRepository` in the dependency lane
  (a typed `__init__` parameter), the methods on the right, with `place` calling `_check`.
- Open `shop/orders/repository.py`: `OrderRepository` is abstract, so its implementation `InMemoryOrderRepository`
  (in `memory.py`) shows too, with the methods it overrides.
- `+ calls` on `create_order` in `shop/orders/api.py` reaches `OrderService.place`; `+ calls` on that block reaches
  `OrderRepository.save` and its in-memory implementation.
- Open `blog/views.py`: the module with `post_list`, and the class-based view `PostDetail` below it, with `View` as
  its parent.
- Ask your AI assistant: *"Show the flow of POST /orders in CodeLanes."* It should give
  `shop.orders.api::create_order` as entry, `shop.orders.service::OrderService.place`, a `binds` hop from
  `shop.orders.repository::OrderRepository.save` to `shop.orders.memory::InMemoryOrderRepository.save`, and a
  `dispatches` hop to `shop.mail::send_order_mail`, the receiver of the `order_placed` signal. All proven, so all solid.
