package dev.codelanes.php

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RouteFilesTest : BasePlatformTestCase() {
    fun testYamlRoutesAreParsed() {
        val yaml = """
            # comment
            order_create:
                path: /api/orders
                controller: App\Controller\OrderController::create
                methods: [POST]

            order_show:
                path: '/api/orders/{id}'
                controller: 'App\Controller\OrderController::show'
                methods: GET|HEAD

            legacy:
                path: /legacy
                defaults: { _controller: App\Controller\LegacyController::run }

            nested_default:
                path: /nested
                defaults:
                    _controller: App\Controller\NestedController

            app_imports:
                resource: ../src/Controller/
                type: attribute
        """.trimIndent()
        assertEquals(
            listOf(
                RouteDef("/api/orders", listOf("POST"), "App\\Controller\\OrderController::create"),
                RouteDef("/api/orders/{id}", listOf("GET", "HEAD"), "App\\Controller\\OrderController::show"),
                RouteDef("/legacy", emptyList(), "App\\Controller\\LegacyController::run"),
                RouteDef("/nested", emptyList(), "App\\Controller\\NestedController"),
            ),
            RouteFiles.parseYaml(yaml),
        )
    }

    fun testXmlRoutesAreParsed() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <routes xmlns="http://symfony.com/schema/routing">
                <route id="order_create" path="/api/orders" controller="App\Controller\OrderController::create" methods="POST"/>
                <route id="legacy" path="/legacy" methods="GET|HEAD">
                    <default key="_controller">App\Controller\LegacyController::run</default>
                </route>
                <import resource="../src/Controller/" type="attribute"/>
            </routes>
        """.trimIndent()
        assertEquals(
            listOf(
                RouteDef("/api/orders", listOf("POST"), "App\\Controller\\OrderController::create"),
                RouteDef("/legacy", listOf("GET", "HEAD"), "App\\Controller\\LegacyController::run"),
            ),
            RouteFiles.parseXml(xml),
        )
    }

    fun testRouteFilesShowUpInFollowARequest() {
        myFixture.addFileToProject(
            "src/Controller/OrderController.php",
            "<?php\nnamespace App\\Controller;\nclass OrderController\n{\n    public function create(): void {}\n}\n",
        )
        myFixture.addFileToProject(
            "src/Controller/InvokeController.php",
            "<?php\nnamespace App\\Controller;\nclass InvokeController\n{\n    public function __invoke(): void {}\n}\n",
        )
        myFixture.addFileToProject(
            "config/routes.yaml",
            "order_create:\n    path: /api/orders\n    controller: App\\Controller\\OrderController::create\n    methods: [POST]\n" +
                "invoke:\n    path: /invoke\n    controller: App\\Controller\\InvokeController\n" +
                "missing:\n    path: /missing\n    controller: App\\Controller\\Nope::x\n",
        )
        val routes = RouteFinder.find(project)
        val labels = routes.map { it.label }
        assertTrue(labels.toString(), labels.contains("POST /api/orders → OrderController::create"))
        assertTrue(labels.toString(), labels.contains("ANY /invoke → InvokeController::__invoke"))
        assertTrue(labels.toString(), labels.none { it.contains("Nope") })
        assertTrue(routes.single { it.methodName == "create" }.filePath.endsWith("OrderController.php"))
    }
}
