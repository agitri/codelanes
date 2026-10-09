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

    private fun writeExternally(relativePath: String, text: String) {
        com.intellij.openapi.application.WriteAction.runAndWait<RuntimeException> {
            val base = project.guessProjectDir()!!
            val dir = com.intellij.openapi.vfs.VfsUtil.createDirectoryIfMissing(base, relativePath.substringBeforeLast('/'))!!
            val file = dir.findChild(relativePath.substringAfterLast('/')) ?: dir.createChildData(this, relativePath.substringAfterLast('/'))
            com.intellij.openapi.vfs.VfsUtil.saveText(file, text)
        }
    }

    private fun read(relativePath: String) = String(project.guessProjectDir()!!.findFileByRelativePath(relativePath)!!.contentsToByteArray())

    fun testATeammatesMarkPulledFromGitIsKeptAndShown() {
        val path = project.guessProjectDir()!!.path + "/src/Pulled.php"
        val reviews = ReviewFile.getInstance(project)
        reviews.put(path, "method:mine", ReviewEntry(ReviewMark.UNDERSTOOD, "", "h1"))
        val pulled = read(".codelanes/review.json").replace("\"method:mine\"", "\"method:theirs\": { \"mark\": \"NEEDS_CHANGE\", \"hash\": \"h2\" },\n    \"method:mine\"")
        writeExternally(".codelanes/review.json", pulled)
        assertEquals(ReviewMark.NEEDS_CHANGE, reviews.get(path, "method:theirs")?.mark)
        reviews.put(path, "method:other", ReviewEntry(ReviewMark.UNCLEAR, "", "h3"))
        val after = ReviewFile(project)
        assertEquals(ReviewMark.NEEDS_CHANGE, after.get(path, "method:theirs")?.mark)
        assertEquals(ReviewMark.UNDERSTOOD, after.get(path, "method:mine")?.mark)
        assertEquals(ReviewMark.UNCLEAR, after.get(path, "method:other")?.mark)
    }

    fun testABrokenReviewFileIsNeverOverwritten() {
        val path = project.guessProjectDir()!!.path + "/src/Conflict.php"
        val conflicted = "{\n<<<<<<< HEAD\n  \"src/A.php\": {}\n=======\n  \"src/B.php\": {}\n>>>>>>> branch\n}\n"
        writeExternally(".codelanes/review.json", conflicted)
        val reviews = ReviewFile.getInstance(project)
        assertFalse(reviews.put(path, "method:x", ReviewEntry(ReviewMark.UNDERSTOOD, "", "h")))
        assertEquals(conflicted, read(".codelanes/review.json"))
        writeExternally(".codelanes/review.json", "{}\n")
    }

    fun testWhitespaceDoesNotCountAsAChange() {
        assertEquals(Review.hash("return 1;"), Review.hash("  return   1;  \n\n"))
        assertFalse(Review.hash("return 1;") == Review.hash("return 2;"))
    }
}
