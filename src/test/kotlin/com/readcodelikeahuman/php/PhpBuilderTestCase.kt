package com.readcodelikeahuman.php

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.BuildResult

abstract class PhpBuilderTestCase : BasePlatformTestCase() {
    protected fun build(text: String, fileName: String = "Foo.php"): BuildResult =
        PhpBlockBuilder.build(myFixture.configureByText(fileName, text))

    protected fun supported(text: String): BlockModel = when (val result = build(text)) {
        is BuildResult.Supported -> result.model
        is BuildResult.Unsupported -> error("Expected Supported but got Unsupported(${result.reason})")
    }

    protected fun addPhp(path: String, text: String) {
        myFixture.addFileToProject(path, text)
    }

    /** The text of [block] as shown on the canvas: its range minus its excluded ranges. */
    protected fun visibleText(block: Block): String {
        val text = myFixture.file.text
        return (block.range.start until block.range.end)
            .filter { offset -> block.excluded.none { it.contains(offset) } }
            .map { text[it] }
            .joinToString("")
    }
}
