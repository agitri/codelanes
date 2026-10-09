package dev.codelanes.shared

import com.google.gson.GsonBuilder
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import java.lang.reflect.Type

/**
 * A JSON file in the project that teammates share through git (e.g. `.codelanes/review.json`).
 *
 * - Re-read whenever it changed on disk (git pull, checkout, a teammate's edit), never served from a stale copy.
 * - [update] applies one change to the freshest content, so it merges with whatever else is in the file.
 * - A file that can't be parsed (e.g. a merge conflict) is never overwritten; the user gets a warning instead.
 */
class SharedJsonFile<T : Any>(
    private val project: Project,
    private val relativePath: String,
    private val type: Type,
    private val empty: () -> T,
) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private var cached: T = empty()
    private var stamp: Long? = null
    private var warned = false

    var broken = false
        private set

    fun read(): T {
        val file = project.guessProjectDir()?.findFileByRelativePath(relativePath)
        val current = file?.modificationStamp ?: -1L
        if (current == stamp) return cached
        stamp = current
        if (file == null) {
            broken = false
            cached = empty()
            return cached
        }
        val text = String(file.contentsToByteArray())
        val parsed = runCatching { gson.fromJson<T>(text, type) }.getOrNull()
        broken = parsed == null && text.isNotBlank()
        if (!broken) warned = false
        cached = parsed ?: empty()
        return cached
    }

    /** Applies [change] to the freshest content and writes it. False (and nothing written) if the file is broken. */
    fun update(change: (T) -> Unit): Boolean {
        val data = read()
        if (broken) {
            warnOnce()
            return false
        }
        change(data)
        val base = project.guessProjectDir() ?: return false
        WriteAction.runAndWait<RuntimeException> {
            val dir = VfsUtil.createDirectoryIfMissing(base, relativePath.substringBeforeLast('/')) ?: return@runAndWait
            val name = relativePath.substringAfterLast('/')
            val file = dir.findChild(name) ?: dir.createChildData(this, name)
            VfsUtil.saveText(file, gson.toJson(data) + "\n")
            stamp = file.modificationStamp
        }
        cached = data
        return true
    }

    /** [path] relative to the project with '/' separators, or null when it lies outside the project. */
    fun relative(path: String): String? {
        val base = project.guessProjectDir()?.path ?: return null
        if (!FileUtil.isAncestor(base, path, false)) return null
        return FileUtil.getRelativePath(base, path, '/')
    }

    fun absolute(path: String): String =
        if (FileUtil.isAbsolutePlatformIndependent(path)) path
        else project.guessProjectDir()?.path?.let { "$it/$path" } ?: path

    private fun warnOnce() {
        if (warned) return
        warned = true
        NotificationGroupManager.getInstance().getNotificationGroup("CodeLanes")
            ?.createNotification(
                "CodeLanes can't read $relativePath (merge conflict?). It won't change the file until it's fixed.",
                NotificationType.WARNING,
            )?.notify(project)
    }
}
