package dev.codelanes.python

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.jetbrains.python.PythonFileType
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import dev.codelanes.lang.MethodRef

/** Every module function and every method of a top-level class in the project's own `.py` files, for Add method… */
object PythonMethodFinder {
    /** Needs indexes and a read action. Sorted by label: `repo::Repo.save  shop.repo`. */
    fun find(project: Project): List<MethodRef> {
        val psiManager = PsiManager.getInstance(project)
        val index = ProjectFileIndex.getInstance(project)
        val result = mutableListOf<MethodRef>()
        for (virtualFile in FileTypeIndex.getFiles(PythonFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
            ProgressManager.checkCanceled()
            if (virtualFile.extension != "py" || !index.isInContent(virtualFile) || index.isInLibrary(virtualFile)) continue
            val file = psiManager.findFile(virtualFile) as? PyFile ?: continue
            val module = PythonBlockBuilder.moduleName(file)
            val short = module.substringAfterLast('.')
            val functions = file.statements.filterIsInstance<PyFunction>() +
                file.statements.filterIsInstance<PyClass>().flatMap { cls -> cls.statementList.statements.filterIsInstance<PyFunction>() }
            for (function in functions) {
                val key = PythonBlockBuilder.keyOf(function)
                result += MethodRef("$short::$key  $module", module, key)
            }
        }
        return result.distinct().sortedBy { it.label }
    }
}
