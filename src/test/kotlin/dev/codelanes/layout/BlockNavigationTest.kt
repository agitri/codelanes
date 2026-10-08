package dev.codelanes.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlockNavigationTest {
    private val rects = mapOf(
        "class" to Rect(180, 0, 200, 300),
        "bar" to Rect(460, 0, 100, 50),
        "footest" to Rect(460, 100, 100, 50),
        "header" to Rect(180, -80, 200, 50),
    )

    @Test
    fun downGoesToTheNextBlockInReadingOrder() {
        assertEquals("footest", BlockNavigation.next(rects, "bar", down = true, allowed = rects.keys))
        assertEquals("bar", BlockNavigation.next(rects, "class", down = true, allowed = rects.keys))
        assertNull(BlockNavigation.next(rects, "footest", down = true, allowed = rects.keys))
    }

    @Test
    fun upGoesToThePreviousBlock() {
        assertEquals("bar", BlockNavigation.next(rects, "footest", down = false, allowed = rects.keys))
        assertEquals("header", BlockNavigation.next(rects, "class", down = false, allowed = rects.keys))
    }

    @Test
    fun skipsBlocksThatCantTakeFocus() {
        assertEquals("footest", BlockNavigation.next(rects, "class", down = true, allowed = setOf("class", "footest")))
    }
}
