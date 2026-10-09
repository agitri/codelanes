package dev.codelanes.review

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import java.security.MessageDigest
import java.util.TreeMap

/** What a reviewer said about a block. */
enum class ReviewMark { UNDERSTOOD, UNCLEAR, NEEDS_CHANGE }

/** What the block shows: the mark, or CHANGED when the code changed after it was marked. */
enum class ReviewStatus { UNDERSTOOD, UNCLEAR, NEEDS_CHANGE, CHANGED }

/** A block's review: optional [mark], optional [note], and a [hash] of the block's code when it was marked. */
data class ReviewEntry(val mark: ReviewMark?, val note: String, val hash: String)

object Review {
    /** Short fingerprint of a block's code, to notice changes after a review. */
    fun hash(text: CharSequence): String =
        MessageDigest.getInstance("SHA-1").digest(text.toString().toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    fun status(entry: ReviewEntry?, currentHash: String): ReviewStatus? {
        val mark = entry?.mark ?: return null
        return if (entry.hash != currentHash) ReviewStatus.CHANGED else ReviewStatus.valueOf(mark.name)
    }
}

/**
 * Review marks and notes, shared in the repository as `.codelanes/review.json` (sorted, so it diffs well in a PR).
 * Keyed by file path relative to the project and block id.
 */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
class ReviewFile(private val project: Project) {
    private class Stored(var mark: String? = null, var note: String? = null, var hash: String? = null)

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val data: TreeMap<String, TreeMap<String, Stored>> = load()

    fun get(filePath: String, id: String): ReviewEntry? {
        val stored = data[relative(filePath)]?.get(id) ?: return null
        return ReviewEntry(stored.mark?.let { runCatching { ReviewMark.valueOf(it) }.getOrNull() }, stored.note.orEmpty(), stored.hash.orEmpty())
    }

    /** Stores [entry] for block [id] of [filePath]; null (or an entry without mark and note) removes it. */
    fun put(filePath: String, id: String, entry: ReviewEntry?) {
        val key = relative(filePath)
        if (entry == null || (entry.mark == null && entry.note.isBlank())) {
            data[key]?.remove(id)
            if (data[key]?.isEmpty() == true) data.remove(key)
        } else {
            data.getOrPut(key) { TreeMap() }[id] = Stored(entry.mark?.name, entry.note.ifBlank { null }, entry.hash)
        }
        save()
    }

    /** A renamed block keeps its review. */
    fun move(filePath: String, from: String, to: String) {
        val entry = get(filePath, from) ?: return
        data[relative(filePath)]?.remove(from)
        put(filePath, to, entry)
    }

    private fun relative(path: String): String {
        val base = project.guessProjectDir()?.path ?: return path
        return path.removePrefix("$base/")
    }

    private fun load(): TreeMap<String, TreeMap<String, Stored>> {
        val file = project.guessProjectDir()?.findFileByRelativePath(PATH) ?: return TreeMap()
        val type = object : TypeToken<TreeMap<String, TreeMap<String, Stored>>>() {}.type
        return runCatching { gson.fromJson<TreeMap<String, TreeMap<String, Stored>>>(String(file.contentsToByteArray()), type) }
            .getOrNull() ?: TreeMap()
    }

    private fun save() {
        val base = project.guessProjectDir() ?: return
        WriteAction.runAndWait<RuntimeException> {
            val dir = VfsUtil.createDirectoryIfMissing(base, DIRECTORY) ?: return@runAndWait
            val file = dir.findChild(FILE_NAME) ?: dir.createChildData(this, FILE_NAME)
            VfsUtil.saveText(file, gson.toJson(data) + "\n")
        }
    }

    companion object {
        fun getInstance(project: Project): ReviewFile = project.getService(ReviewFile::class.java)

        const val DIRECTORY = ".codelanes"
        const val FILE_NAME = "review.json"
        const val PATH = "$DIRECTORY/$FILE_NAME"
    }
}
