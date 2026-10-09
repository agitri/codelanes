package dev.codelanes.php

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class MethodFinderTest : BasePlatformTestCase() {
    fun testEveryProjectMethodIsListedWithItsClass() {
        myFixture.addFileToProject("OrderService.php", "<?php\nnamespace App;\nclass OrderService\n{\n    public function place(string ${'$'}customer, array ${'$'}products): int { return 1; }\n    private function audit(): void {}\n}\n")
        myFixture.addFileToProject("Clock.php", "<?php\nnamespace App;\ninterface Clock\n{\n    public function now(): string;\n}\n")
        val methods = MethodFinder.find(project)
        assertEquals(
            listOf("Clock::now()", "OrderService::audit()", "OrderService::place(string ${'$'}customer, array ${'$'}products)"),
            methods.map { it.label },
        )
        assertEquals("\\App\\OrderService", methods.single { it.method == "place" }.classFqn)
    }
}
