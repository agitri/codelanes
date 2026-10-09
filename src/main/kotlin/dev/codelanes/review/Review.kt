package dev.codelanes.review

import com.google.gson.reflect.TypeToken
import com.intellij.openapi.project.Project
import dev.codelanes.shared.SharedJsonFile
import java.security.MessageDigest
import java.util.TreeMap

/** What a reviewer said about a block. */
enum class ReviewMark { UNDERSTOOD, UNCLEAR, NEEDS_CHANGE }

/** What the block shows: the mark, or CHANGED when the code changed after it was marked. */
enum class ReviewStatus { UNDERSTOOD, UNCLEAR, NEEDS_CHANGE, CHANGED }

/** A block's review: optional [mark], optional [note], and a [hash] of the block's code when it was marked. */
data class ReviewEntry(val mark: ReviewMark?, val note: String, val hash: String)

object Review {
    /** Short fingerprint of a block's code, ignoring whitespace (reformatting isn't a change worth re-reviewing). */
    fun hash(text: CharSequence): String {
        val normalized = text.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
        return MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }

    private val WHITESPACE = Regex("\\s+")

    fun status(entry: ReviewEntry?, currentHash: String): ReviewStatus? {
        val mark = entry?.mark ?: return null
        return if (entry.hash != currentHash) ReviewStatus.CHANGED else ReviewStatus.valueOf(mark.name)
    }
}

/**
 * Review marks and notes, shared in the repository as `.codelanes/review.json` (sorted, so it diffs well in a PR).
 * Keyed by file path relative to the project and block id. Files outside the project are never stored.
 */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
class ReviewFile(project: Project) {
    private class Stored(var mark: String? = null, var note: String? = null, var hash: String? = null)

    private val file = SharedJsonFile<TreeMap<String, TreeMap<String, Stored>>>(
        project, PATH, object : TypeToken<TreeMap<String, TreeMap<String, Stored>>>() {}.type, { TreeMap() },
    )

    fun get(filePath: String, id: String): ReviewEntry? {
        val key = file.relative(filePath) ?: return null
        val stored = file.read()[key]?.get(id) ?: return null
        return ReviewEntry(stored.mark?.let { runCatching { ReviewMark.valueOf(it) }.getOrNull() }, stored.note.orEmpty(), stored.hash.orEmpty())
    }

    /**
     * Stores [entry] for block [id] of [filePath]; null (or an entry without mark and note) removes it.
     * False when nothing was written (file outside the project, or review.json can't be read).
     */
    fun put(filePath: String, id: String, entry: ReviewEntry?): Boolean {
        val key = file.relative(filePath) ?: return false
        return file.update { data ->
            if (entry == null || (entry.mark == null && entry.note.isBlank())) {
                data[key]?.remove(id)
                if (data[key]?.isEmpty() == true) data.remove(key)
            } else {
                data.getOrPut(key) { TreeMap() }[id] = Stored(entry.mark?.name, entry.note.ifBlank { null }, entry.hash)
            }
        }
    }

    /** A renamed block keeps its review. */
    fun move(filePath: String, from: String, to: String) {
        val entry = get(filePath, from) ?: return
        put(filePath, from, null)
        put(filePath, to, entry)
    }

    companion object {
        fun getInstance(project: Project): ReviewFile = project.getService(ReviewFile::class.java)

        const val PATH = ".codelanes/review.json"
    }
}
