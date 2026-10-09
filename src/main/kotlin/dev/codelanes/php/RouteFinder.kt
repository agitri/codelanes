package dev.codelanes.php

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.PhpAttribute
import com.jetbrains.php.lang.psi.elements.PhpClass

/** A request entry point: e.g. "POST /api/orders → OrderController::create". */
data class EntryPoint(val label: String, val filePath: String, val methodName: String)

/**
 * Finds controller actions with a `#[Route]` attribute (Symfony style): class-level prefixes, invokable controllers
 * (`__invoke` with the route on the class), several routes per action, verbs as strings or `Request::METHOD_*`.
 * Paths that aren't a plain string (constants, concatenation, localized arrays) are shown as `?`.
 */
object RouteFinder {
    private data class RouteInfo(val path: String, val verbs: List<String>)

    /** Needs indexes and a read action; checks for cancellation. Sorted by path, then verbs. */
    fun find(project: Project): List<EntryPoint> {
        val scope = GlobalSearchScope.projectScope(project)
        val files = ProjectFileIndex.getInstance(project)
        val candidates = mutableListOf<PsiFile>()
        // Only files that mention "Route" at all: no need to parse the rest of the project.
        PsiSearchHelper.getInstance(project).processAllFilesWithWord("Route", scope, { candidates += it; true }, true)
        val found = mutableListOf<Pair<String, EntryPoint>>()
        for (file in candidates) {
            ProgressManager.checkCanceled()
            if (file !is PhpFile) continue
            val virtualFile = file.virtualFile ?: continue
            if (files.isInLibrary(virtualFile)) continue
            for (phpClass in PsiTreeUtil.findChildrenOfType(file, PhpClass::class.java)) {
                if (phpClass.isAbstract || phpClass.isInterface || phpClass.isTrait) continue // registered on subclasses
                val classRoutes = routesOf(phpClass.attributes)
                for (method in phpClass.ownMethods) {
                    val own = routesOf(method.attributes)
                    val routes = when {
                        own.isNotEmpty() -> own.map { route ->
                            val prefix = classRoutes.firstOrNull()?.path.orEmpty()
                            RouteInfo(join(prefix, route.path), route.verbs)
                        }
                        method.name == "__invoke" -> classRoutes // invokable controller: the class route is the route
                        else -> emptyList()
                    }
                    for (route in routes) {
                        val verbs = route.verbs.ifEmpty { listOf("ANY") }.joinToString("|")
                        val label = "$verbs ${route.path} → ${phpClass.name}::${method.name}"
                        found += route.path to EntryPoint(label, virtualFile.path, method.name)
                    }
                }
            }
        }
        return found.sortedWith(compareBy({ it.first }, { it.second.label })).map { it.second }
    }

    private fun routesOf(attributes: Collection<PhpAttribute>): List<RouteInfo> =
        attributes.filter { it.fqn?.substringAfterLast('\\') == "Route" }.map { attribute ->
            val arguments = attribute.arguments
            val path = arguments.firstOrNull { it.name == "path" }?.argument?.value
                ?: arguments.firstOrNull { it.name.isNullOrEmpty() }?.argument?.value
                ?: "''"
            val verbs = arguments.firstOrNull { it.name == "methods" }?.argument?.value?.let(::verbsOf).orEmpty()
            RouteInfo(plainString(path) ?: "?", verbs)
        }

    /** HTTP verbs from 'POST', ['GET', 'HEAD'] or [Request::METHOD_POST]. */
    private fun verbsOf(expression: String): List<String> {
        val literals = Regex("""['"]([A-Za-z]+)['"]""").findAll(expression).map { it.groupValues[1].uppercase() }
        val constants = Regex("""METHOD_([A-Z]+)""").findAll(expression).map { it.groupValues[1] }
        return (literals + constants).distinct().toList()
    }

    /** The text of a single quoted string literal, or null for anything else. */
    private fun plainString(expression: String): String? {
        val text = expression.trim()
        val match = Regex("""^'([^']*)'$|^"([^"]*)"$""").find(text) ?: return null
        return match.groupValues[1].ifEmpty { match.groupValues[2] }
    }

    private fun join(prefix: String, path: String): String {
        if (prefix == "?" || path == "?") return "?"
        val joined = prefix.trimEnd('/') + "/" + path.trimStart('/')
        return if (joined.length > 1) joined.trimEnd('/') else joined
    }
}
