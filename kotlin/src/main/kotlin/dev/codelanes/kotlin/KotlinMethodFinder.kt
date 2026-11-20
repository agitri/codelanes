package dev.codelanes.kotlin

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.codelanes.lang.MethodRef
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile

/** Every top-level function and every member function of a top-level class in the project's own `.kt` files, for Add method… */
object KotlinMethodFinder {
    /**
     * Needs indexes and a read action. Sorted by label: `Repo::fun save(note: Note)  acme`. The owner is the class fqn
     * (members) or the module fqn (top-level functions), the key the one a callee id carries: `save`, `Companion.create`.
     */
    fun find(project: Project): List<MethodRef> {
        val psiManager = PsiManager.getInstance(project)
        val index = ProjectFileIndex.getInstance(project)
        val result = mutableListOf<MethodRef>()
        for (virtualFile in FileTypeIndex.getFiles(KotlinFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
            ProgressManager.checkCanceled()
            if (virtualFile.extension != "kt" || !index.isInContent(virtualFile) || index.isInLibrary(virtualFile)) continue
            if (!FileTypeRegistry.getInstance().isFileOfType(virtualFile, KotlinFileType.INSTANCE)) continue
            val file = psiManager.findFile(virtualFile) as? KtFile ?: continue
            for (function in KotlinBlockBuilder.canvasFunctions(file)) {
                val calleeKey = KotlinBlockBuilder.calleeKey(function) ?: continue
                val owner = calleeKey.substringBefore("::")
                result += MethodRef(
                    "${owner.substringAfterLast('.')}::${KotlinBlockBuilder.functionTitle(function)}  ${file.packageFqName.asString()}",
                    owner, calleeKey.substringAfter("::"),
                )
            }
        }
        return result.distinct().sortedBy { it.label }
    }
}
