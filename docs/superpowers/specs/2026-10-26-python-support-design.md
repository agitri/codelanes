# Python support (PyCharm) — design

Date: 2026-10-26. Status: layout approved in chat ("Module canvas"); the user asked to go straight to building, so
the rest of this spec records decisions made on their behalf.

## Goal

CodeLanes works for Python in PyCharm (and IntelliJ IDEA with the Python plugin): open a `.py` file as a canvas, follow
calls across modules, review and note as in PHP and Java, find Django, FastAPI and Flask routes in Follow a Request,
and let an AI assistant show Python request flows through the existing MCP tools. Version 0.3.0.

## Rules

- Nothing PHP or Java users have today changes. All current tests stay green without edits to what they assert; PHP
  and Java block ids stay the same.
- The canvas, review, notes, working sets, flows and MCP tools stay language-neutral.
- Python lives in its own content module (`python/`, `codelanes.python`), depending only on the free `PythonCore`
  plugin, so it loads in PyCharm (any edition) and in IntelliJ IDEA with "Python Community Edition".
- Every IntelliJ class the module uses must be visible to a content module in every supported version: check with
  `scripts/check-module-classes.py` (see Build) — the tests can't catch this (it bit 0.2.0).

## The module canvas

Every `.py` file opens as a canvas, except `.pyi` stubs ("Stub file") and files in libraries ("Library code"). An
empty file opens with just its module block.

| Python | Block | Id |
|---|---|---|
| `import` / `from … import` lines at the top | HEADER, collapsed, `N imports` | `header` (as today) |
| the module: docstring and every top-level statement that isn't an import, `def` or `class` | MODULE (new kind), centre | `module:<dotted module name>` |
| each top-level `class` (decorators and docstring included) | CLASS, centre, below the module, source order | `class:<module>.<Class>` |
| each top-level `def` (decorators included) | METHOD, right, OWNS from the module | `method:<name>` |
| each method of a class | METHOD, right, OWNS from its class | `method:<Class>.<name>` |
| base classes | PARENT, left, EXTENDS to the class | `parent:<qualified name>` |
| injected types (below) | DEPENDENCY, left, INJECTS from the class | `dependency:<qualified name>` |
| for a class with subclasses that is abstract (`ABC` base, `@abstractmethod`, or `Protocol`): project subclasses | IMPLEMENTER + IMPLEMENTATION, as in Java | as in Java |

- Nested classes and functions stay inside their parent block. `if TYPE_CHECKING:` / `if __name__ == "__main__":`
  blocks are module statements.
- The CLASS block excludes its methods' ranges, the MODULE block excludes classes, functions and the header, as today.
- Titles: `module shop.orders.views`, `class OrderService(BaseService)`, `def place(self, customer: str, items: int)`
  (`async def …` for coroutines), the parameters as written.
- The dotted module name comes from the source root (`shop/orders/views.py` → `shop.orders.views`); `__init__.py` is
  its package (`shop.orders`).

### Injected types (the dependency lane)

The annotated parameter types of each class's `__init__`, and for a `@dataclass` or a pydantic-style class (a base
named `BaseModel` or `BaseSettings`, or any class with annotated class attributes and no `__init__`) its annotated
class attributes — only when the annotation resolves to a class in the project's own code (not the standard library,
not site-packages). `Optional[X]`, `X | None`, `list[X]`, `Annotated[X, …]` count as `X`. Duplicates dropped.

### Lines

- **calls**: a call inside a function or method that resolves (with type inference) to another function or method of
  this module.
- **overrides**: a method links to each base class (on the canvas) that defines a method with the same name.
- **+ calls**: calls into other project modules become CALLEE blocks (`callee:<module>::<key>`, key as above), as in
  PHP/Java; calls to an abstract method also show its project implementations.
- **+ parents** digs through base classes, as in Java.
- Resolution uses PyCharm's type inference. Untyped code resolves less: those lines are simply missing (documented).

### Types without source

A base class whose source isn't in the project (standard library, site-packages) shows as a collapsed block with its
name and method list (`Block.sourceless`), as Java's library types do.

## Layout: several centre blocks

`LayoutEngine` today assumes one CLASS block. New rule: the **centre** is the MODULE block if there is one, plus every
CLASS block. All centre blocks share one rank (the highest any of them gets), stacked top to bottom: the module first,
then the classes in source order, with the header right above the module. Their OWNS children share the next rank,
grouped by owner in the same order. Left lanes (parents, dependencies) keep today's staircase. With one CLASS and no
MODULE (PHP, Java) every layout stays exactly as it is.

Anything that takes "the class block" (`ofKind(CLASS).firstOrNull()`) for a Python model uses the centre's first
block (the module) instead — orphan callees hang off it.

## Session behaviour

- **+ method** on the module block adds `def new_function():\n    pass` at the end of the file; on a class block
  `def new_method(self):\n    pass` at the end of the class body, indented one level deeper than the class. Python has
  no closing brace, so the insertion point comes from the language support (new `LanguageSupport` hook with a default
  that keeps today's brace logic).
- Delete, Remove from canvas, review marks (key `method:` + the part after `::`), notes, pins and working sets work
  unchanged with the ids above.
- **Add method…** lists every function and method in the project's own `.py` files.

## Follow a Request

Routes found without AI, labelled `VERB path → <module short name>::<key>` (e.g. `POST /orders → views::create_order`),
sorted by path. The entry's method is the block key, so picking it opens the file and follows `method:<key>`.

- **FastAPI**: decorators `@<x>.get/post/put/delete/patch/head/options("/path")` and
  `@<x>.api_route("/path", methods=[...])`, where `<x>` is assigned `FastAPI(...)` or `APIRouter(...)` in the same
  module; an `APIRouter(prefix="/orders")` prefix is prepended. `include_router` prefixes from other modules are not
  followed.
- **Flask**: `@<x>.route("/path", methods=[...])` (default GET) and Flask 2 `@<x>.get/post/…("/path")`, where `<x>` is
  assigned `Flask(...)` or `Blueprint(..., url_prefix="/x")` in the same module (prefix prepended).
- **Django**: every `urls.py` (or a module named in `ROOT_URLCONF`). `path()` / `re_path()` entries pointing at a
  function view give `ANY /path`; at `SomeView.as_view()` give one route per HTTP method the class defines (`get`,
  `post`, `put`, `patch`, `delete`) labelled with that verb and key `SomeView.<verb>`. `include("app.urls")` is
  followed with its prefix; a `urls.py` that some other `urls.py` includes is only listed through that include. The
  path is shown as written, with a leading `/`.
- Paths that aren't string literals (or constants of this module) show as `?`.
- Out of scope: Django REST Framework routers, FastAPI `include_router` prefixes across modules, Flask
  `add_url_rule`, Starlette `Route(...)` lists.

## AI flows for Python

- References: `shop.orders.services::OrderService.place`, `shop.orders.services::place_order`,
  `shop.orders.services::OrderService` for a class, `shop.orders.services::OrderService.__init__` for its constructor
  (the class block). Same `::` separator as PHP and Java; the module part is dotted.
- A new `LanguageSupport` hook turns a found target into the canvas block id for the open file (default: today's
  `FlowResolver` logic), because Python ids carry the class inside the key.
- Proven hops: `calls` (the `from` function calls `to`, or instantiates its class), `binds` (`to` overrides `from`),
  `dispatches` (`from` calls `send`/`send_robust` on the `to` object, or instantiates its class), `listens` (`to` is
  decorated `@receiver(<from>)`, or its first non-self parameter is annotated with the `from` class). `middleware`
  as `calls`; `routes` never.
- The MCP tool description gets one sentence on Python references.

## Product

- Version 0.3.0, `sinceBuild` stays 252.
- Marketplace description and README: "PHP (PhpStorm), Java (IntelliJ IDEA) and Python (PyCharm)".
- `verifyPlugin` adds PyCharm 2025.2, 2025.3, 2026.1, 2026.2.
- `./gradlew runPyCharm` starts a sandbox PyCharm; `runIde` (IntelliJ IDEA) also gets PythonCore, so Java and Python
  can be tried in one sandbox.
- `playground/python/`: a tiny FastAPI app, a Flask blueprint and a Django app (framework names stubbed, so it opens
  without installing anything).

## Build

- Gradle submodule `python/`, content module `codelanes.python` (`<plugin id="PythonCore"/>`), wired like `:java`.
- The development target gets `compatiblePlugin("PythonCore")`; tests run with it.
- `scripts/check-module-classes.py`: lists every `com.intellij`/`com.jetbrains` class each of the plugin's jars uses and
  flags ones that, in a given IDE, live only in a `lib/modules/` (content-module) jar. Run it against the cached
  PyCharm and IntelliJ IDEA versions before release.

## Testing

- Every existing test passes unchanged.
- Python fixture tests (light fixture, no Python SDK: stub what's needed): which files open; every block and id above;
  titles; header/module/class ranges; the layout rule (several centre blocks; PHP/Java snapshots unchanged); calls,
  overrides, dependencies (`__init__`, dataclass, pydantic, `Optional`/`Annotated` unwrapping, stdlib left out);
  `+ calls` across modules; implementers of an ABC; `+ method` on module and class; routes for each framework,
  including prefixes, CBVs, `include()`, `?` paths; flow references and each proven hop kind.
- Manual: `runPyCharm` with `playground/python`.

## Out of scope

- Jupyter notebooks, `.pyi` stubs as canvases, Cython.
- DRF routers, cross-module FastAPI prefixes, Starlette/Sanic/aiohttp/Pyramid routes.
- Kotlin, JAX-RS/Micronaut (later projects).
