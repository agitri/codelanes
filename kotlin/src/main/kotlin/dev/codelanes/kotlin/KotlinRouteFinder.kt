package dev.codelanes.kotlin

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import dev.codelanes.java.JavaRouteFinder
import dev.codelanes.lang.EntryPoint
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Spring routes in Kotlin classes: the same rules as Java, applied to each class's light class (Kotlin annotations
 * show up on the light methods), then mapped back to the Kotlin function so Follow a Request opens the `.kt` file.
 */
object KotlinRouteFinder {
    private val WORDS = listOf("GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping")

    /** Needs indexes and a read action; checks for cancellation. Sorted by path, then label. */
    fun find(project: Project): List<EntryPoint> {
        val index = ProjectFileIndex.getInstance(project)
        val candidates = linkedSetOf<PsiFile>()
        val search = PsiSearchHelper.getInstance(project)
        for (word in WORDS) {
            search.processAllFilesWithWord(word, GlobalSearchScope.projectScope(project), { candidates += it; true }, true)
        }
        val found = mutableListOf<Pair<String, EntryPoint>>()
        for (file in candidates) {
            ProgressManager.checkCanceled()
            if (file !is KtFile) continue
            val virtualFile = file.virtualFile ?: continue
            if (virtualFile.extension != "kt" || index.isInLibrary(virtualFile)) continue
            if (!FileTypeRegistry.getInstance().isFileOfType(virtualFile, KotlinFileType.INSTANCE)) continue
            for (cls in file.declarations.filterIsInstance<KtClass>()) {
                val light = cls.toLightClass() ?: continue
                for ((path, labelled) in JavaRouteFinder.routesIn(light)) {
                    val function = (labelled.second as? KtLightMethod)?.kotlinOrigin as? KtNamedFunction ?: continue
                    found += path to EntryPoint(labelled.first, virtualFile.path, KotlinBlockBuilder.functionKey(function))
                }
            }
        }
        return found.distinctBy { it.second.label }.sortedWith(compareBy({ it.first }, { it.second.label })).map { it.second }
    }
}
