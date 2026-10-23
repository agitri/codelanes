package dev.codelanes.java

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.codelanes.lang.MethodRef

/** Every method of every top-level Java type in the project's own code, for Add method… */
object JavaMethodFinder {
    /** Needs indexes and a read action. Sorted by label. */
    fun find(project: Project): List<MethodRef> {
        val psiManager = PsiManager.getInstance(project)
        val files = ProjectFileIndex.getInstance(project)
        val result = mutableListOf<MethodRef>()
        for (virtualFile in FileTypeIndex.getFiles(JavaFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
            ProgressManager.checkCanceled()
            if (files.isInLibrary(virtualFile)) continue
            val file = psiManager.findFile(virtualFile) as? PsiJavaFile ?: continue
            for (cls in file.classes) {
                val fqn = JavaBlockBuilder.fqn(cls)
                for (method in JavaBlockBuilder.ownMethods(cls)) {
                    result += MethodRef("${cls.name}::${JavaBlockBuilder.methodTitle(method)}  ${file.packageName}", fqn, JavaBlockBuilder.methodKey(method))
                }
            }
        }
        return result.sortedBy { it.label }
    }
}
