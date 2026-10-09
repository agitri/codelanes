package dev.codelanes.review

import com.intellij.openapi.project.guessProjectDir
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ReviewTest : BasePlatformTestCase() {
    fun testAMarkStaysWhileTheCodeIsUnchangedAndTurnsIntoChangedAfterwards() {
        val entry = ReviewEntry(ReviewMark.UNDERSTOOD, "", Review.hash("return 1;"))
        assertEquals(ReviewStatus.UNDERSTOOD, Review.status(entry, Review.hash("return 1;")))
        assertEquals(ReviewStatus.CHANGED, Review.status(entry, Review.hash("return 2;")))
        assertNull(Review.status(null, Review.hash("x")))
        assertNull(Review.status(ReviewEntry(null, "just a note", ""), Review.hash("x")))
    }

    fun testMarksAndNotesAreSavedInTheRepoAndReadBack() {
        val path = project.guessProjectDir()!!.path + "/src/Order.php"
        val file = ReviewFile(project)
        file.put(path, "method:total", ReviewEntry(ReviewMark.UNCLEAR, "why float?", "abc"))
        val saved = project.guessProjectDir()!!.findFileByRelativePath(".codelanes/review.json")
        assertNotNull(saved)
        assertTrue(String(saved!!.contentsToByteArray()).contains("why float?"))
        assertEquals(ReviewEntry(ReviewMark.UNCLEAR, "why float?", "abc"), ReviewFile(project).get(path, "method:total"))
    }

    fun testRemovingAndMovingEntries() {
        val path = project.guessProjectDir()!!.path + "/src/Order.php"
        val file = ReviewFile(project)
        file.put(path, "method:a", ReviewEntry(ReviewMark.NEEDS_CHANGE, "", "h"))
        file.move(path, "method:a", "method:b")
        assertNull(file.get(path, "method:a"))
        assertEquals(ReviewMark.NEEDS_CHANGE, file.get(path, "method:b")!!.mark)
        file.put(path, "method:b", null)
        assertNull(ReviewFile(project).get(path, "method:b"))
    }
}
