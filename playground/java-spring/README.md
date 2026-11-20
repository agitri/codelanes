# Java Spring playground

A small Spring-style shop, in Java with a little Kotlin, to try CodeLanes in IntelliJ IDEA (or Android Studio). The Spring and Lombok annotations are stubs in `src/`,
so it opens without Maven or Gradle: **File → Open…** this folder (`playground/java-spring`, not `playground`), and
if the file icons in the Project view show a coffee cup instead of a blue **C**, right-click `src` → **Mark Directory
as → Sources Root**. Outside a source root IDEA can't resolve the types, so the dependency lane and the lines stay
empty.

The Kotlin files (`src/com/acme/shop/stock/`) sit in the same source root. If Kotlin types show as unresolved, the
IDE offers to configure Kotlin for the module; accept it (or add the Kotlin runtime library).

Try:
- **Follow a Request…** lists `POST /orders → OrderController::create`; pick it to see the chain down to the
  repository.
- Open `OrderService.java`: the repository and the event publisher sit in the dependency lane (Lombok), `place` is
  overloaded, `check` is called by `place`.
- `+ calls` on `OrderController.create` reaches `OrderService.place`; `+ calls` on that block reaches
  `OrderRepository.save` and its `JpaOrderRepository` implementation.
- Ask your AI assistant: *"Show the flow of POST /orders in CodeLanes."* It should give
  `com.acme.shop.order.OrderController::create` as entry, `OrderService::place(String,int)`, a `dispatches` hop to
  `com.acme.shop.order.OrderPlaced::class` and a `listens` hop to `com.acme.shop.mail.SendOrderMail::on`.
- Kotlin and Java mixed: open `ExpressOrderController.kt` (a Kotlin `@RestController`): it opens as a canvas, and
  `+ calls` on `create` reaches the Java `OrderService.place`. **Follow a Request…** lists
  `POST /orders/express → ExpressOrderController::create` next to the Java route. Open `OrderService.java`: the
  Kotlin `StockRepository` shows in the dependency lane and `+ calls` on `place` shows its `reserve` as Kotlin source.
- AI flow with Kotlin: `com.acme.shop.stock.ExpressOrderController::create` then
  `com.acme.shop.order.OrderService::place(String,int)` then `com.acme.shop.stock.StockRepository::reserve`; all
  `calls` are proven from the code.
