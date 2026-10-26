package dev.codelanes.python

class PythonRouteFinderTest : PythonTestCase() {
    override fun setUp() {
        super.setUp()
        addModule("fastapi.py", "class FastAPI: ...\nclass APIRouter: ...")
        addModule("flask.py", "class Flask: ...\nclass Blueprint: ...")
    }

    private fun labels() = PythonRouteFinder.find(project).map { it.label }

    fun testFastApi() {
        addModule(
            "shop/api.py",
            """
            from fastapi import FastAPI, APIRouter
            app = FastAPI()
            router = APIRouter(prefix="/orders")

            @app.get("/health")
            def health(): ...

            @router.post("")
            async def create_order(): ...

            @router.api_route("/{id}", methods=["GET", "HEAD"])
            def show(id: int): ...
            """,
        )
        assertEquals(
            listOf("GET /health → api::health", "POST /orders → api::create_order", "GET|HEAD /orders/{id} → api::show"),
            labels(),
        )
    }

    fun testFlask() {
        addModule(
            "shop/web.py",
            """
            from flask import Flask, Blueprint
            app = Flask(__name__)
            bp = Blueprint("orders", __name__, url_prefix="/shop")

            @app.route("/")
            def index(): ...

            @bp.route("/orders", methods=["POST", "PUT"])
            def save(): ...

            @bp.get("/orders/<int:id>")
            def show(id): ...
            """,
        )
        assertEquals(
            listOf("GET / → web::index", "POST|PUT /shop/orders → web::save", "GET /shop/orders/<int:id> → web::show"),
            labels(),
        )
    }

    fun testPathFromAModuleConstantElseUnknown() {
        addModule(
            "api.py",
            """
            from fastapi import FastAPI
            app = FastAPI()
            PATH = "/x"

            @app.get(PATH)
            def a(): ...

            @app.get(compute())
            def b(): ...
            """,
        )
        assertEquals(listOf("GET /x → api::a", "GET ? → api::b"), labels().sorted().let { l -> listOf(l.first { "api::a" in it }, l.first { "api::b" in it }) })
    }

    fun testOtherReceiversAreNotRoutes() {
        addModule(
            "svc.py",
            """
            class Cache:
                def get(self, key): ...
            cache = Cache()

            @cache.get("x")
            def f(): ...
            """,
        )
        assertEmpty(labels())
    }

    fun testMethodsInsideAClassUseTheClassKey() {
        addModule(
            "views.py",
            """
            from fastapi import APIRouter
            router = APIRouter()

            class Orders:
                @router.get("/orders")
                def list(self): ...
            """,
        )
        val route = PythonRouteFinder.find(project).single()
        assertEquals("GET /orders → views::Orders.list", route.label)
        assertEquals("Orders.list", route.methodName)
        assertTrue(route.filePath.endsWith("views.py"))
    }

    fun testNestedPackageUsesTheLastModuleSegment() {
        addModule("shop/__init__.py", "")
        addModule("shop/orders.py", "from fastapi import FastAPI\napp = FastAPI()\n@app.delete('/o')\ndef drop(): ...")
        assertEquals(listOf("DELETE /o → orders::drop"), labels())
    }
}
