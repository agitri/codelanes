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
        // sorted by path, so routes of one resource sit together
        assertEquals(
            listOf("POST /api/orders → OrderController::create", "GET|HEAD /api/orders/{id} → OrderController::show"),
            routes.map { it.label },
        )
        val create = routes.single { it.methodName == "create" }
        assertTrue(create.filePath.endsWith("OrderController.php"))
    }

    fun testCommonSymfonyRoutePatterns() {
        myFixture.addFileToProject("Route.php", "<?php\nnamespace Symfony\\Component\\Routing\\Attribute;\n#[\\Attribute]\nclass Route { public function __construct(mixed ${'$'}path = '', array|string ${'$'}methods = []) {} }\n")
        myFixture.addFileToProject("Request.php", "<?php\nnamespace Symfony\\Component\\HttpFoundation;\nclass Request { const METHOD_POST = 'POST'; }\n")
        myFixture.addFileToProject(
            "Controllers.php",
            """
            <?php
            namespace App;

            use Symfony\Component\HttpFoundation\Request;
            use Symfony\Component\Routing\Attribute\Route;

            #[Route('/invoke', methods: 'POST')]
            class InvokableController
            {
                public function __invoke(): void {}
            }

            class OtherController
            {
                private const P = '/p';

                #[Route('/a', methods: [Request::METHOD_POST])]
                #[Route('/a2')]
                public function a(): void {}

                #[Route(self::P . '/c')]
                public function c(): void {}
            }

            #[Route('/base')]
            abstract class BaseController
            {
                #[Route('/x')]
                public function x(): void {}
            }
            """.trimIndent(),
        )
        val labels = RouteFinder.find(project).map { it.label }
        assertTrue(labels.toString(), labels.contains("POST /invoke → InvokableController::__invoke"))
        assertTrue(labels.toString(), labels.contains("POST /a → OtherController::a"))
        assertTrue(labels.toString(), labels.contains("ANY /a2 → OtherController::a"))
        assertTrue(labels.toString(), labels.contains("ANY ? → OtherController::c"))
        assertTrue(labels.toString(), labels.none { it.contains("BaseController") })
    }
}
