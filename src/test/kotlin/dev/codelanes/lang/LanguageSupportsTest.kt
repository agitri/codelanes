package dev.codelanes.lang

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.codelanes.php.PhpLanguageSupport

class LanguageSupportsTest : BasePlatformTestCase() {
    fun testAPhpFileIsHandledByThePhpSupport() {
        val file = myFixture.configureByText("Foo.php", "<?php\nclass Foo {}\n").virtualFile
        assertTrue(LanguageSupports.forFile(file) is PhpLanguageSupport)
    }

    fun testAPlainTextFileHasNoSupport() {
        val file = myFixture.configureByText("notes.txt", "hello").virtualFile
        assertNull(LanguageSupports.forFile(file))
    }

    fun testThePhpSupportWritesAPhpMethod() {
        val php = LanguageSupports.all().single { it is PhpLanguageSupport }
        assertEquals("    public function go(): void\n    {\n    }\n", php.newMethodText("go", "    ", inInterface = false))
        assertEquals("    public function go(): void;\n", php.newMethodText("go", "    ", inInterface = true))
    }
}
