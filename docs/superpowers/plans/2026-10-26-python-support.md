# Python Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** CodeLanes 0.3.0 shows Python files as a module canvas in PyCharm, finds Django/FastAPI/Flask routes in
Follow a Request, and resolves Python references in AI flows — without changing anything for PHP and Java.

**Architecture:** A new Gradle submodule `python/` is a content module (`codelanes.python`, depends on the free
`PythonCore` plugin) that registers `PythonLanguageSupport` on the existing `dev.codelanes.languageSupport` extension
point, exactly like `java/`. The canvas gets one generalisation (several centre blocks: a new MODULE block plus every
CLASS) and two small `LanguageSupport` hooks (where "+ method" inserts, and a flow target's block id), each with a
default that keeps today's PHP/Java behaviour.

**Tech Stack:** Kotlin 2.4.21, IntelliJ Platform Gradle Plugin 2.19.0, IntelliJ IDEA 2026.1 + PythonCore as the
development target, JUnit 3 light fixture tests. Gradle needs
`JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home`.

**Spec:** `docs/superpowers/specs/2026-10-26-python-support-design.md` (read it; it is the authority).

**Ruling (plan style):** the PythonCore PSI API was not checked while writing this plan, so steps give the exact
interfaces, ids, titles and test cases, and leave the PSI code to the implementer. Task 1 checks the API and the test
fixture first; later tasks build on what it finds (see its report).

## Global Constraints

- Nothing PHP or Java users have today changes; every existing test passes without edits to what it asserts.
- Python code lives only in `python/src/main/kotlin/dev/codelanes/python/` (package `dev.codelanes.python`); the main
  module never imports `com.jetbrains.python`.
- Content module `codelanes.python`, descriptor `python/src/main/resources/codelanes.python.xml`, dependency
  `<plugin id="PythonCore"/>` only.
- Ids: module `module:<dotted>`, class `class:<dotted module>.<Class>`, top-level function `method:<name>`, method
  `method:<Class>.<name>`, callee `callee:<dotted module>::<key>`, parent `parent:<qualified>`, dependency
  `dependency:<qualified>`.
- Version `0.3.0`; `sinceBuild` stays `252`; no `untilBuild`.
- Every IntelliJ class used by the Python module must not live only in a `lib/modules/` jar of any supported IDE:
  check with `python3 scripts/check-module-classes.py` (Task 1 creates it).
- Commits: author email `40357662+agitri@users.noreply.github.com`; message ends with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01EZuytWB9WWzUrFUKH7R1Ac`. Never push.
- Run tests with `./gradlew test` (never `test clean` in one invocation).

## Review Focus

1. A Python file with only top-level statements (a script, `settings.py`) — expect a module block with the code and no
   crash on zero classes/functions (Task 3 test `testScriptWithoutDefs`).
2. A class with a method named like a module function (`def save()` at top level and `Repo.save`) — ids must not
   collide (`method:save` vs `method:Repo.save`) (Task 3 test `testSameNameInModuleAndClass`).
3. Decorated functions and `async def` — the decorators are inside the block range and the title says `async def`
   (Task 3 test `testDecoratorsAndAsync`).
4. Untyped code — calls that don't resolve simply produce no line, never an exception (Task 4 test
   `testUnresolvedCallsAreSkipped`).
5. A Django project where one `urls.py` includes another — the included routes appear once, with the prefix (Task 8
   test `testIncludeIsFollowedOnce`).

---

### Task 1: The `python/` content module, test base and class-visibility script

**Files:**
- Create: `python/build.gradle.kts`, `python/src/main/resources/codelanes.python.xml`,
  `python/src/main/kotlin/dev/codelanes/python/PythonLanguageSupport.kt`
- Create: `scripts/check-module-classes.py`
- Create: `src/test/kotlin/dev/codelanes/python/PythonTestCase.kt`, `src/test/kotlin/dev/codelanes/python/PythonFixtureTest.kt`
- Modify: `settings.gradle.kts`, `build.gradle.kts`, `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Produces: `PythonLanguageSupport : LanguageSupport` with `handles` = file type is Python and extension `py`;
  `unsupportedReason` = `"Stub file"` for `.pyi`, `"Library code"` for files in libraries, else null; other methods
  return empty results / `BuildResult.Unsupported("Not built yet")` until later tasks.
- Produces: `abstract class PythonTestCase : BasePlatformTestCase` (or the light fixture the spike finds works) with
  `protected fun addModule(path: String, text: String): PsiFile` (adds `path` under the source root, trimIndent) and
  `protected fun open(path: String, text: String): PsiFile` (adds and configures it in the editor).
- Produces: `scripts/check-module-classes.py <plugin zip> <ide dir>...` printing, per jar of the plugin, `ok` or the
  classes that only exist in a `lib/modules/` or `plugins/*/lib/modules/` jar of that IDE (exit 1 if any). Java
  classes missing entirely from an IDE without Java (and the same for PHP, Python) are not errors.

- [ ] **Step 1:** Mirror `java/` exactly: `python/build.gradle.kts` like `java/build.gradle.kts` but
  `compatiblePlugin("PythonCore")` (or `bundledPlugin` if the target bundles it); `include(":python")` in
  `settings.gradle.kts`; in root `build.gradle.kts` add `compatiblePlugin("PythonCore")` to the development target,
  `pluginModule(project(":python"))` and `testImplementation(project(":python"))`; `<module name="codelanes.python"/>`
  in `plugin.xml`'s `<content>`; descriptor:

```xml
<!-- Content module: loaded only when the Python plugin is there (PyCharm, or IntelliJ IDEA with Python). -->
<idea-plugin package="dev.codelanes.python">
    <dependencies>
        <plugin id="PythonCore"/>
    </dependencies>
    <extensions defaultExtensionNs="dev.codelanes">
        <languageSupport implementation="dev.codelanes.python.PythonLanguageSupport"/>
    </extensions>
</idea-plugin>
```

- [ ] **Step 2: Write the failing fixture test** — proves the fixture can resolve across project modules with type
  inference (everything later depends on it):

```kotlin
class PythonFixtureTest : PythonTestCase() {
    fun testACallResolvesIntoAnotherModule() {
        addModule("shop/__init__.py", "")
        addModule("shop/repo.py", """
            class Repo:
                def save(self, order):
                    pass
        """)
        val file = open("shop/service.py", """
            from shop.repo import Repo

            class Service:
                def __init__(self, repo: Repo):
                    self.repo = repo

                def place(self):
                    self.repo.save(1)
        """)
        val call = PsiTreeUtil.findChildrenOfType(file, PyCallExpression::class.java).single { it.text.startsWith("self.repo.save") }
        val context = TypeEvalContext.codeAnalysis(project, file)
        val resolved = call.multiResolveCalleeFunction(PyResolveContext.defaultContext(context)).single()
        assertEquals("save", resolved.name)
        assertEquals("Repo", (resolved as PyFunction).containingClass?.name)
    }

    fun testSupportHandlesPyAndRejectsStubs() {
        val support = PythonLanguageSupport()
        assertTrue(support.handles(open("a.py", "x = 1").virtualFile))
        assertEquals("Stub file", support.unsupportedReason(open("b.pyi", "x: int")))
    }
}
```

  (Use whichever resolve API PythonCore 2026.1 has; the assertion — `self.repo.save` resolves to `Repo.save` through the
  `repo: Repo` annotation — is the requirement.)

- [ ] **Step 3:** Run `./gradlew test --tests 'dev.codelanes.python.*'` — expect FAIL (classes missing), then make it
  pass. If the light fixture can't resolve without a Python SDK, find the smallest setup that does (e.g. a mock SDK
  from PythonCore's test framework if published, or a heavier fixture) and record which in the report — every later
  task uses `PythonTestCase`.
- [ ] **Step 4:** Write `scripts/check-module-classes.py` (Python 3, stdlib only; uses `javap -v` from `$JAVA_HOME`).
  Run it on the built zip against every cached IDE (`~/.gradle/caches/*/transforms/*/transformed/{PhpStorm,idea,ideaIU,ideaIC,PyCharm}*`).
  Expected: `ok` everywhere (the Python jar has almost nothing yet).
- [ ] **Step 5:** `./gradlew test` (all green, count printed), `./gradlew buildPlugin`, then commit
  `feat(python): python content module, test base, module class check`.
- [ ] **Report:** the fixture that works, the PSI entry points you used (resolve, type eval, qualified names, source
  root of a file), and the PythonCore jar layout (main jar vs `lib/modules/`) — later tasks need these.

### Task 2: Several centre blocks in the layout (MODULE kind)

**Files:**
- Modify: `src/main/kotlin/dev/codelanes/model/BlockModel.kt` (add `BlockKind.MODULE`)
- Modify: `src/main/kotlin/dev/codelanes/layout/LayoutEngine.kt`
- Modify: any `when (kind)` over `BlockKind` that must treat MODULE like CLASS (grep `BlockKind.CLASS` in
  `src/main/kotlin`: canvas styling, header buttons, review, folding)
- Test: `src/test/kotlin/dev/codelanes/layout/LayoutEngineTest.kt`

**Interfaces:**
- Produces: `BlockKind.MODULE`; `LayoutEngine.centre(model): List<Block>` (internal) = the MODULE block (if any)
  followed by all CLASS blocks in model order. Everything that used `ofKind(CLASS).firstOrNull()` uses `centre.first()`.

- [ ] **Step 1: Failing tests** (build `BlockModel`s by hand, like the existing layout tests):
  - `testModuleAndClassesShareTheCentreColumn`: blocks `header`(HEADER), `module:m`(MODULE), `class:m.A`, `class:m.B`,
    `method:f` (OWNS from module), `method:A.x` (OWNS from A), `method:B.y` (OWNS from B), `parent:Base` (EXTENDS → A).
    Assert: module, A, B have the same x; module above A above B; header directly above module (same x);
    `method:f`, `method:A.x`, `method:B.y` share one x right of the centre, in that top-to-bottom order;
    `parent:Base` is left of the centre.
  - `testAClassWithoutParentsStillLinesUpWithOneThatHasThem`: as above but only B has a parent; A and B same x.
  - `testOrphanCalleeHangsOffTheModule`: a CALLEE with no incoming chain link lands right of the methods column.
  - Every existing `LayoutEngineTest` stays green unchanged (PHP/Java: one CLASS, no MODULE).
- [ ] **Step 2:** Run — FAIL. **Step 3:** Implement: after ranking, set every centre block to the max centre rank and
  its OWNS children to centre+1 (only when the model has a MODULE or more than one CLASS); order the centre column
  module-first then model order; order the next column by owner position, then existing order; header rank/placement
  follow `centre.first()`. **Step 4:** `./gradlew test` green. **Step 5:** commit
  `feat(layout): several centre blocks (module and classes)`.

### Task 3: `PythonBlockBuilder` — module, classes, functions, ids, titles

**Files:**
- Create: `python/src/main/kotlin/dev/codelanes/python/PythonBlockBuilder.kt`
- Modify: `PythonLanguageSupport.build` → `PythonBlockBuilder.build`
- Test: `src/test/kotlin/dev/codelanes/python/PythonBlockBuilderStructureTest.kt`

**Interfaces:**
- Produces: `object PythonBlockBuilder { fun build(file: PyFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult; fun moduleName(file: PsiFile): String; fun keyOf(function: PyFunction): String /* "name" or "Class.name" */; fun functionTitle(function: PyFunction): String }`

- [ ] **Step 1: Failing tests**, one per behaviour, e.g. for `shop/orders/views.py`:

```python
"""Order endpoints."""
import os
from shop.orders.service import OrderService

router = make_router()

def create_order(payload):
    return OrderService().place(payload)

class OrderView(Base):
    """A view."""
    template = "x.html"

    def get(self, request):
        return render(request)

    @staticmethod
    async def post(request, *, dry_run: bool = False):
        pass
```

  Expected ids: `header`, `module:shop.orders.views`, `method:create_order`, `class:shop.orders.views.OrderView`,
  `method:OrderView.get`, `method:OrderView.post`, `parent:…` comes in Task 4 (not here). Titles:
  `module shop.orders.views`, `2 imports` header (collapsed), `class OrderView(Base)`, `def create_order(payload)`,
  `def get(self, request)`, `async def post(request, *, dry_run: bool = False)`. Visible text of the module block
  contains the docstring and `router = make_router()` but not `def create_order` or `class OrderView`; the class block
  contains `"""A view."""` and `template = "x.html"` but not `def get`; `method:OrderView.post` starts at
  `@staticmethod`. OWNS links: module→`method:create_order`, class→both methods.
  More tests: `testScriptWithoutDefs` (only statements: module block + header, no crash), `testSameNameInModuleAndClass`
  (`def save()` top level and `class Repo: def save(self)` → `method:save` and `method:Repo.save`),
  `testDecoratorsAndAsync`, `testInitPyIsItsPackage` (`shop/orders/__init__.py` → `module:shop.orders`),
  `testEmptyFile` (module block only), `testNestedDefsStayInside` (an inner `def` is no block).
- [ ] **Steps 2–5:** run FAIL, implement, `./gradlew test` green, commit `feat(python): module canvas blocks`.

### Task 4: Lanes and lines — parents, dependencies, overrides, calls

**Files:**
- Modify: `PythonBlockBuilder.kt`
- Test: `src/test/kotlin/dev/codelanes/python/PythonBlockBuilderLanesTest.kt`

**Interfaces:**
- Consumes: Task 3 ids. Produces: PARENT/DEPENDENCY blocks and EXTENDS/INJECTS/OVERRIDES/CALLS links per the spec.

- [ ] **Step 1: Failing tests:**
  - parents: `class A(Base, Mixin)` with both in the project → `parent:shop.base.Base`, `parent:shop.base.Mixin`
    (collapsed, summary = their method titles), EXTENDS → `class:…A`; `+ parents` reveals the parent's parent (same
    `revealed` mechanism as Java). A base from outside the project's content (put a class in a library root, as the
    Java tests do with junit) → `sourceless = true`. `object` and `Generic[T]`/`Protocol` bases are not blocks.
  - dependencies: `__init__(self, repo: Repo, mailer: Optional[Mailer], name: str, items: list[Item], clock: Annotated[Clock, "x"])`
    → `dependency:` for Repo, Mailer, Item, Clock (all project classes), not `str`; `@dataclass class C: repo: Repo`
    → Repo; `class S(BaseModel): repo: Repo` → Repo; duplicates once; INJECTS from the class.
  - overrides: `class B(A): def save(self)` where A defines `save` and A is on the canvas → OVERRIDES
    `method:B.save` → `parent:…A`.
  - calls: `def a(): b()` and `def b(): pass` → CALLS `method:a` → `method:b`; `self.check()` inside a method → CALLS
    `method:C.place` → `method:C.check`; `testUnresolvedCallsAreSkipped`: `def a(x): x.whatever()` → no link, no error.
- [ ] **Steps 2–5:** run FAIL, implement, green, commit `feat(python): parents, dependencies, overrides, calls`.

### Task 5: `+ calls` across modules and implementers of abstract classes

**Files:**
- Modify: `PythonBlockBuilder.kt`
- Test: `src/test/kotlin/dev/codelanes/python/PythonBlockBuilderCallsTest.kt`

**Interfaces:**
- Consumes: Task 3/4. Produces: CALLEE blocks `callee:<module>::<key>` with CALLS_INTO links, MORE blocks and caps
  exactly as `JavaBlockBuilder.followCalls` (10 callees per method, 80-block chain); IMPLEMENTER/IMPLEMENTATION blocks
  as `JavaBlockBuilder.implementedBy` (10 implementers, 5 implementations).

- [ ] **Step 1: Failing tests:** following `method:Service.place` (in `followedCalls`) that calls
  `self.repo.save(o)` (repo: Repo) and `notify(o)` (function in `shop/mail.py`) → `callee:shop.repo::Repo.save`,
  `callee:shop.mail::notify`; `added` containing `shop.mail::notify` shows it without following; a call into the
  standard library or site-packages makes no block; an abstract `class Repo(ABC)` with `@abstractmethod save` and
  `class SqlRepo(Repo)` → following a call to `Repo.save` also shows `callee:shop.repo::SqlRepo.save` (IMPLEMENTED_BY);
  opening `Repo` itself shows `SqlRepo` as IMPLEMENTER with its `save`. Use the platform's `DefinitionsScopedSearch` or
  Python's own inheritor search only if `scripts/check-module-classes.py` passes for it.
- [ ] **Steps 2–5:** run FAIL, implement, green, run the class check script, commit
  `feat(python): + calls across modules and implementers`.

### Task 6: Session hooks — `+ method`, delete, flow ids, Add method…

**Files:**
- Modify: `src/main/kotlin/dev/codelanes/lang/LanguageSupport.kt` (two hooks with defaults)
- Modify: `src/main/kotlin/dev/codelanes/canvas/BlocksSession.kt` (`addMethod`, `deleteLines`, `addToCanvas`),
  the canvas header button that triggers "+ method" (show it on MODULE and CLASS blocks)
- Modify: `src/main/kotlin/dev/codelanes/flow/FlowResolver.kt` (`idOf` asks the hook first)
- Modify: `PythonLanguageSupport.kt`; Create: `python/src/main/kotlin/dev/codelanes/python/PythonMethodFinder.kt`
- Test: `src/test/kotlin/dev/codelanes/python/PythonSessionTest.kt`, plus existing PHP/Java session tests unchanged

**Interfaces:**
- Produces in `LanguageSupport`:

```kotlin
/** What "+ method" inserts into [owner] (a CLASS or MODULE block, currently spanning [ownerRange] of [text]), or null
 *  to use the brace rule with [newMethodText]. [taken]: ids of the METHOD blocks on the canvas. */
fun methodInsertion(text: CharSequence, owner: Block, ownerRange: SourceRange, taken: Set<String>, indent: String): MethodInsertion? = null

/** The block id [target] gets on a canvas opened on [openFqn], or null for the default rule. */
fun blockIdOf(target: FlowTarget, openFqn: String): String? = null

data class MethodInsertion(val offset: Int, val text: String, val name: String, val blockId: String)
```

- `BlocksSession.addMethod(ownerId: String? = null)` (null = first centre block, today's behaviour);
  `deleteLines` bounds to the range of the centre block that OWNS the method; `addToCanvas` treats `module:<fqn>` like
  `class:<fqn>` when checking whether a method belongs to the open file.
- `PythonMethodFinder.find(project): List<MethodRef>` — `MethodRef("<module short>::<key>  <dotted module>", <dotted module>, <key>)`.

- [ ] **Step 1: Failing tests:** `+ method` on the module of `def a():\n    pass\n` appends
  `\n\ndef new_function():\n    pass\n` and the block `method:new_function` exists with its name selected; on
  `class C:\n    def a(self):\n        pass\n\nx = 1\n` inserts `def new_method(self):\n        pass` inside C (before
  `x = 1`) → `method:C.new_method`; a second press gives `new_method2`; delete of `method:C.a` removes exactly its
  lines; FlowResolver gives `class:shop.service.Service` for a Python class target in the open module and
  `callee:shop.repo::Repo.save` for one elsewhere (via the hook); `PythonMethodFinder` lists module functions and
  methods, not library ones. All PHP/Java session and flow tests unchanged.
- [ ] **Steps 2–5:** run FAIL, implement, green, commit `feat(python): + method, delete, flow ids and Add method…`.

### Task 7: Routes — FastAPI and Flask

**Files:**
- Create: `python/src/main/kotlin/dev/codelanes/python/PythonRouteFinder.kt` (FastAPI/Flask; Task 8 adds Django)
- Modify: `PythonLanguageSupport.routes`
- Test: `src/test/kotlin/dev/codelanes/python/PythonRouteFinderTest.kt`

**Interfaces:**
- Produces: `object PythonRouteFinder { fun find(project: Project): List<EntryPoint> }`, sorted by path then label,
  `EntryPoint("VERB /path → <module short>::<key>", filePath, key)`.

- [ ] **Step 1: Failing tests** (framework classes stubbed as plain project classes `fastapi.FastAPI`,
  `fastapi.APIRouter`, `flask.Flask`, `flask.Blueprint` — match by the assigned call's name, so no import resolution
  is required):

```python
# shop/api.py
from fastapi import FastAPI, APIRouter
app = FastAPI()
router = APIRouter(prefix="/orders")

@app.get("/health")
def health(): ...

@router.post("")
async def create_order(): ...

@router.api_route("/{id}", methods=["GET", "HEAD"])
def show(id: int): ...
```

  → `GET /health → api::health`, `POST /orders → api::create_order`, `GET|HEAD /orders/{id} → api::show`.

```python
# shop/web.py
from flask import Flask, Blueprint
app = Flask(__name__)
bp = Blueprint("orders", __name__, url_prefix="/shop")

@app.route("/")
def index(): ...

@bp.route("/orders", methods=["POST", "PUT"])
def save(): ...

@bp.get("/orders/<int:id>")
def show(id): ...
```

  → `GET / → web::index`, `POST|PUT /shop/orders → web::save`, `GET /shop/orders/<int:id> → web::show`.
  Also: a non-literal path (`@app.get(PATH)` where `PATH = "/x"` in the module → `/x`; anything else → `?`);
  `@cache.get("x")` where `cache` isn't a FastAPI/Flask/Router/Blueprint object → no route; methods inside a class
  decorated the same way → key `Class.method`.
- [ ] **Steps 2–5:** run FAIL, implement, green, commit `feat(python): FastAPI and Flask routes`.

### Task 8: Routes — Django

**Files:**
- Modify: `PythonRouteFinder.kt` (or create `DjangoUrls.kt` next to it if it grows past ~150 lines)
- Test: `src/test/kotlin/dev/codelanes/python/DjangoRoutesTest.kt`

- [ ] **Step 1: Failing tests** (`django.urls.path`, `re_path`, `include` matched by name):

```python
# site/urls.py
from django.urls import path, include
urlpatterns = [
    path("health/", views.health),
    path("shop/", include("shop.urls")),
]
# shop/urls.py
from django.urls import path, re_path
from shop import views
urlpatterns = [
    path("orders/", views.create_order, name="create"),
    re_path(r"^orders/(?P<id>\d+)/$", views.OrderView.as_view()),
]
# shop/views.py
def create_order(request): ...
class OrderView(View):
    def get(self, request, id): ...
    def delete(self, request, id): ...
```

  → `ANY /health/ → views::health` (site/views.py stub), `ANY /shop/orders/ → views::create_order`,
  `DELETE /shop/^orders/(?P<id>\d+)/$ → views::OrderView.delete`, `GET /shop/^orders/(?P<id>\d+)/$ → views::OrderView.get`.
  `testIncludeIsFollowedOnce`: `shop/urls.py`'s routes appear only with the `/shop/` prefix, not also on their own.
  A view that doesn't resolve (`views.missing`) → route with key `missing` is skipped (not listed).
- [ ] **Steps 2–5:** run FAIL, implement, green, commit `feat(python): Django routes`.

### Task 9: AI flows for Python

**Files:**
- Create: `python/src/main/kotlin/dev/codelanes/python/PythonFlowLookup.kt`
- Modify: `PythonLanguageSupport` (`findFlowTarget`, `proves`, `blockIdOf`); the MCP tool description (grep
  `Java references use dots` in `src/main/kotlin/dev/codelanes/mcp`)
- Test: `src/test/kotlin/dev/codelanes/python/PythonFlowLookupTest.kt`

**Interfaces:**
- Produces: references `<dotted module>::<function>`, `::<Class>.<method>`, `::<Class>`, `::<Class>.__init__`
  (the class block). `findFlowTarget` returns null when the part before `::` contains `\` or isn't a project Python
  module (so PHP/Java keep winning their references); `FlowTarget(classFqn = <dotted module>, declaringFqn = <dotted
  module of the defining file>, methodKey = <key> or null, …)`; inherited `Child.save` resolves to `Base.save` in its
  own module.

- [ ] **Step 1: Failing tests:** each reference shape found / missing with a reason (`shop.x::nope` →
  `Missing("shop.x::nope (no such function)")`, wording like Java's); proven vs claimed for `calls` (direct call, and
  instantiating a class), `binds` (override), `dispatches` (`order_placed.send(...)` where `order_placed` is a
  module-level object of class `Signal` and `to` is that class or `from` instantiates `to`), `listens`
  (`@receiver(order_placed)` on `to`, and first non-self parameter annotated with the `from` class), `routes` never;
  a whole flow through `FlowResolver.resolve` with a Python entry gives ids `method:create_order`,
  `callee:shop.service::OrderService.place`.
- [ ] **Steps 2–5:** run FAIL, implement, green, commit `feat(python): AI flow references and proofs`.

### Task 10: Product — playground, docs, verification, version

**Files:**
- Create: `playground/python/` (FastAPI app, Flask blueprint, Django app; framework modules stubbed in
  `playground/python/stubs/{fastapi,flask,django}` so it opens with no installs) and its `README.md`
- Modify: `.gitignore` (track `playground/python/` like `playground/java-spring/`), `build.gradle.kts` (version
  `0.3.0`; `verifyPlugin` + PyCharm 2025.2, 2025.3, 2026.1, 2026.2 — use the `IntelliJPlatformType` names the plugin
  offers; `runPyCharm` task like `runPhpStorm`), `plugin.xml` description, `README.md` (Python section like "Java and
  Spring", install, Develop), `CHANGELOG.md` (`## 0.3.0`), `docs/roadmap.md`, `qodana.sarif.json` if paths moved

- [ ] **Step 1:** Playground + docs. **Step 2:** `./gradlew test`, `./gradlew clean buildPlugin`, `./gradlew verifyPlugin`
  → every IDE Compatible (paste the summary lines in the report); `python3 scripts/check-module-classes.py` → ok for
  every cached IDE. **Step 3:** commit `docs(python): playground, README, 0.3.0`.
