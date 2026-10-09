package dev.codelanes.php

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RouteFinderTest : BasePlatformTestCase() {
    fun testRoutesAreFoundWithTheirClassPrefixAndMethods() {
        myFixture.addFileToProject("Route.php", "<?php\nnamespace Symfony\\Component\\Routing\\Attribute;\n#[\\Attribute]\nclass Route { public function __construct(string \$path = '', array \$methods = []) {} }\n")
        myFixture.addFileToProject(
            "OrderController.php",
            """
            <?php
            namespace App\Controller;

            use Symfony\Component\Routing\Attribute\Route;

            #[Route('/api')]
            class OrderController
            {
                #[Route('/orders', methods: ['POST'])]
                public function create(): void {}

                #[Route(path: '/orders/{id}', methods: ['GET', 'HEAD'])]
                public function show(int ${'$'}id): void {}

                public function helper(): void {}
            }
            """.trimIndent(),
        )
        val routes = RouteFinder.find(project)
        assertEquals(
            listOf("GET|HEAD /api/orders/{id} → OrderController::show", "POST /api/orders → OrderController::create"),
            routes.map { it.label },
        )
        val create = routes.single { it.methodName == "create" }
        assertTrue(create.filePath.endsWith("OrderController.php"))
    }
}
