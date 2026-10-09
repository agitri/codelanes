package dev.codelanes.php

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.PhpClass

/** A method that can be dropped on a canvas, e.g. "OrderService::place(string $customer)". */
data class MethodRef(val label: String, val classFqn: String, val method: String)

/** Lists every method of every class, interface, trait and enum in the project's own code. */
object MethodFinder {
    /** Needs indexes and a read action. Sorted by label. */
    fun find(project: Project): List<MethodRef> {
        val psiManager = PsiManager.getInstance(project)
        val files = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
        val result = mutableListOf<MethodRef>()
        for (virtualFile in FileTypeIndex.getFiles(PhpFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
            com.intellij.openapi.progress.ProgressManager.checkCanceled()
            if (files.isInLibrary(virtualFile)) continue
            val file = psiManager.findFile(virtualFile) as? PhpFile ?: continue
            for (phpClass in PsiTreeUtil.findChildrenOfType(file, PhpClass::class.java).filterNot { it.isAnonymous }) {
                for (method in phpClass.ownMethods) {
                    result += MethodRef("${phpClass.name}::${PhpBlockBuilder.methodTitle(method)}  ${phpClass.namespaceName.trim('\\')}", phpClass.fqn, method.name)
                }
            }
        }
        return result.sortedBy { it.label }
    }
}
