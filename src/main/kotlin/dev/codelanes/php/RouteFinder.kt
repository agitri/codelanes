package dev.codelanes.php

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.PhpFileType
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpClass

/** A request entry point: e.g. "POST /api/orders → OrderController::create". */
data class EntryPoint(val label: String, val filePath: String, val methodName: String)

/** Finds controller actions with a `#[Route]` attribute (Symfony style, including a class-level prefix). */
object RouteFinder {
    private data class RouteInfo(val path: String, val verbs: List<String>)

    /** Needs indexes and a read action. Sorted by label. */
    fun find(project: Project): List<EntryPoint> {
        val psiManager = PsiManager.getInstance(project)
        val result = mutableListOf<EntryPoint>()
        for (virtualFile in FileTypeIndex.getFiles(PhpFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
            val file = psiManager.findFile(virtualFile) as? PhpFile ?: continue
            for (phpClass in PsiTreeUtil.findChildrenOfType(file, PhpClass::class.java)) {
                val prefix = routeOf(phpClass.attributes)?.path.orEmpty()
                for (method in phpClass.ownMethods) {
                    val route = routeOf(method.attributes) ?: continue
                    val verbs = route.verbs.ifEmpty { listOf("ANY") }.joinToString("|")
                    result += EntryPoint("$verbs ${join(prefix, route.path)} → ${phpClass.name}::${method.name}", virtualFile.path, method.name)
                }
            }
        }
        return result.sortedBy { it.label }
    }

    private fun routeOf(attributes: Collection<PhpAttribute>): RouteInfo? {
        val attribute = attributes.firstOrNull { it.fqn?.substringAfterLast('\\') == "Route" } ?: return null
        val arguments = attribute.arguments
        val path = arguments.firstOrNull { it.name == "path" }?.argument?.value
            ?: arguments.firstOrNull { it.name.isNullOrEmpty() }?.argument?.value
            ?: ""
        val verbs = arguments.firstOrNull { it.name == "methods" }?.argument?.value
            ?.let { list -> Regex("[A-Za-z]+").findAll(list).map { it.value.uppercase() }.toList() }
            .orEmpty()
        return RouteInfo(path.trim().trim('\'', '"'), verbs)
    }

    private fun join(prefix: String, path: String): String {
        val joined = prefix.trimEnd('/') + "/" + path.trimStart('/')
        return if (joined.length > 1) joined.trimEnd('/') else joined
    }
}
