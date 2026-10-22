# Java Spring playground

A small Spring-style shop to try CodeLanes in IntelliJ IDEA. The Spring and Lombok annotations are stubs in `src/`,
so it opens without Maven or Gradle: **File → Open…** this folder, mark `src` as Sources Root if IDEA doesn't.

Try:
- Open `OrderService.java`: the repository and the event publisher sit in the dependency lane (Lombok), `place` is
  overloaded, `check` is called by `place`.
- `+ calls` on `OrderController.create` reaches `OrderRepository.save` and its `JpaOrderRepository` implementation.
- Ask your AI assistant: *"Show the flow of POST /orders in CodeLanes."* It should give
  `com.acme.shop.order.OrderController::create` as entry, `OrderService::place(String,int)`, a `dispatches` hop to
  `com.acme.shop.order.OrderPlaced::class` and a `listens` hop to `com.acme.shop.mail.SendOrderMail::on`.
