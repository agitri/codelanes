package dev.codelanes.python

class DjangoRoutesTest : PythonTestCase() {
    override fun setUp() {
        super.setUp()
        addModule("django/urls.py", "def path(route, view, name=None): ...\ndef re_path(route, view, name=None): ...\ndef include(arg): ...")
        addModule("django/views.py", "class View: ...")
        addModule("shop/views.py", "from django.views import View\n\ndef create_order(request): ...\n\nclass OrderView(View):\n    def get(self, request, id): ...\n    def delete(self, request, id): ...\n    def helper(self): ...")
        addModule("site/views.py", "def health(request): ...")
    }

    private fun labels() = PythonRouteFinder.find(project).map { it.label }

    private fun shopUrls() = addModule(
        "shop/urls.py",
        """
        from django.urls import path, re_path
        from shop import views
        urlpatterns = [
            path("orders/", views.create_order, name="create"),
            re_path(r"^orders/(?P<id>\d+)/$", views.OrderView.as_view()),
            path("gone/", views.missing),
        ]
        """,
    )

    private fun siteUrls(include: String = "include(\"shop.urls\")") = addModule(
        "site/urls.py",
        """
        from django.urls import path, include
        from site import views
        urlpatterns = [
            path("health/", views.health),
            path("shop/", $include),
        ]
        """,
    )

    fun testIncludeWithPrefix() {
        shopUrls(); siteUrls()
        assertEquals(
            listOf(
                "ANY /health/ → views::health",
                "DELETE /shop/^orders/(?P<id>\\d+)/$ → views::OrderView.delete",
                "GET /shop/^orders/(?P<id>\\d+)/$ → views::OrderView.get",
                "ANY /shop/orders/ → views::create_order",
            ),
            labels(),
        )
    }

    fun testIncludeIsFollowedOnce() {
        shopUrls(); siteUrls()
        assertTrue(labels().none { it.startsWith("ANY /orders/") || it.contains("/^orders") && !it.contains("/shop/") })
    }

    fun testIncludedModuleReference() {
        shopUrls()
        addModule(
            "site/urls.py",
            """
            from django.urls import path, include
            from shop import urls as shop_urls
            urlpatterns = [path("shop/", include(shop_urls))]
            """,
        )
        assertTrue(labels().contains("ANY /shop/orders/ → views::create_order"))
        assertTrue(labels().none { it.startsWith("ANY /orders/") })
    }

    fun testUnincludedUrlsAreListedOnTheirOwn() {
        shopUrls()
        assertEquals(
            listOf("DELETE /^orders/(?P<id>\\d+)/$ → views::OrderView.delete", "GET /^orders/(?P<id>\\d+)/$ → views::OrderView.get", "ANY /orders/ → views::create_order"),
            labels(),
        )
    }

    fun testIncludeCycleTerminates() {
        addModule("a/urls.py", "from django.urls import path, include\nfrom shop import views\nurlpatterns = [path('a/', views.create_order), path('b/', include('b.urls'))]")
        addModule("b/urls.py", "from django.urls import path, include\nfrom shop import views\nurlpatterns = [path('x/', views.create_order), path('a/', include('a.urls'))]")
        labels() // must return
    }

    private fun urlsWith(view: String, extra: String = "") = addModule(
        "site/urls.py",
        """
        from django.urls import path
        from shop import views, cbv
        $extra
        urlpatterns = [path("orders/", $view)]
        """,
    )

    fun testInheritedFromProjectBase() {
        addModule("shop/base.py", "class BaseView:\n    def get(self, request): ...")
        addModule("shop/cbv.py", "from shop.base import BaseView\n\nclass OrderList(BaseView): ...")
        urlsWith("cbv.OrderList.as_view()")
        assertEquals(listOf("GET /orders/ → cbv::OrderList"), labels())
        assertEquals("OrderList", PythonRouteFinder.find(project).single().methodName)
    }

    fun testInheritedFromLibraryStubAndOwnVerbs() {
        addModule("django/generic.py", "class ListView:\n    def get(self, request): ...")
        addModule("shop/cbv.py", "from django.generic import ListView\n\nclass OrderList(ListView):\n    def post(self, request): ...")
        urlsWith("cbv.OrderList.as_view()")
        assertEquals(listOf("GET /orders/ → cbv::OrderList", "POST /orders/ → cbv::OrderList.post"), labels())
    }

    fun testUnresolvableBaseIsAny() {
        addModule("shop/cbv.py", "from nowhere import ListView\n\nclass OrderList(ListView): ...")
        urlsWith("cbv.OrderList.as_view()")
        assertEquals(listOf("ANY /orders/ → cbv::OrderList"), labels())
    }

    fun testInlineInclude() {
        addModule("site/urls.py", "from django.urls import path, include\nfrom shop import views\nurlpatterns = [path('api/', include([path('a/', views.create_order)]))]")
        assertEquals(listOf("ANY /api/a/ → views::create_order"), labels())
    }

    fun testMutualIncludeCycleStillListsRoutes() {
        addModule("a/urls.py", "from django.urls import path, include\nfrom shop import views\nurlpatterns = [path('a/', views.create_order), path('b/', include('b.urls'))]")
        addModule("b/urls.py", "from django.urls import path, include\nfrom shop import views\nurlpatterns = [path('x/', views.OrderView.as_view()), path('a/', include('a.urls'))]")
        val l = labels()
        assertEquals(l.toString(), 1, l.count { it.startsWith("ANY /a/ ") })
        assertTrue(l.toString(), l.any { it.startsWith("GET /b/x/") })
    }

    fun testTupleIncludeWithANamespace() {
        shopUrls()
        siteUrls("include((\"shop.urls\", \"shop\"), namespace=\"shop\")")
        assertTrue(labels().toString(), labels().contains("ANY /shop/orders/ → views::create_order"))
        assertTrue(labels().none { it.startsWith("ANY /orders/") })
    }

    fun testTupleIncludeOfAModuleReference() {
        shopUrls()
        addModule(
            "site/urls.py",
            """
            from django.urls import path, include
            from shop import urls as shop_urls
            urlpatterns = [path("shop/", include((shop_urls, "shop")))]
            """,
        )
        assertTrue(labels().toString(), labels().contains("ANY /shop/orders/ → views::create_order"))
        assertTrue(labels().none { it.startsWith("ANY /orders/") })
    }

    fun testTupleIncludeOfAnInlineList() {
        addModule("site/urls.py", "from django.urls import path, include\nfrom shop import views\nurlpatterns = [path('api/', include(([path('a/', views.create_order)], 'api')))]")
        assertEquals(listOf("ANY /api/a/ → views::create_order"), labels())
    }

    fun testOwnVerbsOfARedefinedClassUseItsKey() {
        addModule("shop/cbv.py", "from django.views import View\n\nclass OrderList(View): ...\n\nclass OrderList(View):\n    def get(self, request): ...")
        urlsWith("cbv.OrderList.as_view()")
        assertEquals(listOf("GET /orders/ → cbv::OrderList#2.get"), labels())
    }
}
