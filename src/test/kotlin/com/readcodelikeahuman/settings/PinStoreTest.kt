package com.readcodelikeahuman.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.readcodelikeahuman.layout.Point

class PinStoreTest : BasePlatformTestCase() {
    private val file = "/project/src/Foo.php"
    private val other = "/project/src/Foo.php.bak"

    override fun setUp() {
        super.setUp()
        PinStore.getInstance(project).prune(file, emptySet())
        PinStore.getInstance(project).prune(other, emptySet())
    }

    fun testPinsAreStoredPerFile() {
        val store = PinStore.getInstance(project)
        store.pin(file, "method:bar", Point(10, 20))
        store.pin(other, "method:bar", Point(1, 2))
        assertEquals(mapOf("method:bar" to Point(10, 20)), store.pins(file))
    }

    fun testPruneDropsPinsOfVanishedBlocks() {
        val store = PinStore.getInstance(project)
        store.pin(file, "method:bar", Point(10, 20))
        store.pin(file, "method:baz", Point(30, 40))
        store.prune(file, setOf("method:baz"))
        assertEquals(mapOf("method:baz" to Point(30, 40)), store.pins(file))
    }

    fun testBlocksByDefaultIsOnInitially() {
        assertTrue(BlocksSettings().state.openAsBlocksByDefault)
    }
}
