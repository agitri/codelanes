package com.readcodelikeahuman

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.php.lang.psi.PhpFile

class SmokeTest : BasePlatformTestCase() {
    fun testPhpPluginParsesPhpFiles() {
        val file = myFixture.configureByText("A.php", "<?php\nclass A {}\n")
        assertTrue("Expected PhpFile but got ${file.javaClass}", file is PhpFile)
    }
}
