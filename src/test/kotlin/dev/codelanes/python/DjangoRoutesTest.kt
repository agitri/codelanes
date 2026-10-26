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
}
