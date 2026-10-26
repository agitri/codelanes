package dev.codelanes.python

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyArgumentList
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyDecorator
import com.jetbrains.python.psi.PyDecoratorList
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyReferenceExpression
import dev.codelanes.lang.EntryPoint

/**
 * FastAPI and Flask routes: `@app.get("/x")`, `@router.post(...)`, `@bp.route("/x", methods=[...])`,
 * `@router.api_route(...)` on module functions and methods of top-level classes. The receiver must be a module-level
 * `name = FastAPI(...) / APIRouter(...) / Flask(...) / Blueprint(...)` in the same file (matched by the call's name, so
 * no import resolution is needed); its `prefix=` / `url_prefix=` is prepended. Paths are literals or a module-level
 * string constant, anything else is shown as `?`.
 */
object PythonRouteFinder {
    private val VERBS = setOf("get", "post", "put", "delete", "patch", "head", "options")
    private val ROUTE_METHODS = setOf("route", "api_route")
    private val APP_CLASSES = mapOf("FastAPI" to "prefix", "APIRouter" to "prefix", "Flask" to null, "Blueprint" to "url_prefix")
    private val STRING = Regex("""^([rRbBuU]?)("{3}|'{3}|"|')(.*)\2$""", RegexOption.DOT_MATCHES_ALL)

    /** Needs indexes and a read action; checks for cancellation. Sorted by path, then label. */
    fun find(project: Project): List<EntryPoint> {
        val files = ProjectFileIndex.getInstance(project)
        val candidates = linkedSetOf<PsiFile>()
        // Only files that mention a route decorator or a framework class: no need to parse the rest of the project.
        val search = PsiSearchHelper.getInstance(project)
        val words = VERBS + ROUTE_METHODS + "route" + APP_CLASSES.keys
        for (word in words) {
            search.processAllFilesWithWord(word, GlobalSearchScope.projectScope(project), { candidates += it; true }, true)
        }
        val found = mutableListOf<Pair<String, EntryPoint>>()
        for (candidate in candidates) {
            ProgressManager.checkCanceled()
            val file = candidate as? PyFile ?: continue
            val virtualFile = file.virtualFile ?: continue
            if (virtualFile.extension != "py" || files.isInLibrary(virtualFile)) continue
            val apps = appsOf(file)
            if (apps.isEmpty()) continue
            val constants = constantsOf(file)
            val short = PythonBlockBuilder.moduleName(file).substringAfterLast('.')
            for (function in PythonBlockBuilder.canvasFunctions(file)) {
                for (decorator in decoratorsOf(function)) {
                    val route = routeOf(decorator, apps, constants) ?: continue
                    val key = PythonBlockBuilder.keyOf(function)
                    found += route.second to EntryPoint("${route.first} ${route.second} → $short::$key", virtualFile.path, key)
                }
            }
        }
        return found.distinctBy { it.second.label }.sortedWith(compareBy({ it.first }, { it.second.label })).map { it.second }
    }

    private fun decoratorsOf(function: PyFunction): List<PyDecorator> =
        PsiTreeUtil.getChildOfType(function, PyDecoratorList::class.java)
            ?.let { PsiTreeUtil.getChildrenOfTypeAsList(it, PyDecorator::class.java) }.orEmpty()

    private fun argumentsOf(call: PyCallExpression): List<PyExpression> =
        PsiTreeUtil.getChildOfType(call, PyArgumentList::class.java)
            ?.let { PsiTreeUtil.getChildrenOfTypeAsList(it, PyExpression::class.java) }.orEmpty()

    /** `name=value` → (name, value text); anything else → null. */
    private fun keyword(argument: PyExpression): Pair<String, String>? {
        val match = Regex("""^(\w+)\s*=(?!=)\s*(.*)$""", RegexOption.DOT_MATCHES_ALL).find(argument.text) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun callOf(expression: PyExpression?): PyCallExpression? = expression as? PyCallExpression

    /** Module-level `name = FastAPI(...)` etc. → name to its route prefix ("" or "?"). */
    private fun appsOf(file: PyFile): Map<String, String> {
        val apps = mutableMapOf<String, String>()
        for (statement in file.statements.filterIsInstance<PyAssignmentStatement>()) {
            val parts = PsiTreeUtil.getChildrenOfTypeAsList(statement, PyExpression::class.java)
            if (parts.size != 2) continue
            val call = callOf(parts[1]) ?: continue
            val className = call.firstChild.text.substringAfterLast('.').trim()
            if (className !in APP_CLASSES) continue
            val prefixKeyword = APP_CLASSES[className]
            val prefix = argumentsOf(call).firstNotNullOfOrNull { arg -> keyword(arg)?.takeIf { it.first == prefixKeyword } }
                ?.let { literal(it.second, emptyMap()) } ?: ""
            apps[parts[0].text] = prefix
        }
        return apps
    }

    /** Module-level `NAME = "literal"` assignments. */
    private fun constantsOf(file: PyFile): Map<String, String> {
        val constants = mutableMapOf<String, String>()
        for (statement in file.statements.filterIsInstance<PyAssignmentStatement>()) {
            val parts = PsiTreeUtil.getChildrenOfTypeAsList(statement, PyExpression::class.java)
            if (parts.size != 2) continue
            val value = STRING.matchEntire(parts[1].text.trim()) ?: continue
            if (value.groupValues[1].lowercase() == "b") continue
            constants[parts[0].text] = value.groupValues[3]
        }
        return constants
    }

    /** A string literal's value, a module constant's value, else "?". */
    private fun literal(text: String, constants: Map<String, String>): String {
        val trimmed = text.trim()
        constants[trimmed]?.let { return it }
        val match = STRING.matchEntire(trimmed) ?: return "?"
        return if (match.groupValues[1].lowercase() == "b") "?" else match.groupValues[3]
    }

    /** (verbs joined by `|`, full path) for a route decorator, or null. */
    private fun routeOf(decorator: PyDecorator, apps: Map<String, String>, constants: Map<String, String>): Pair<String, String>? {
        val call = PsiTreeUtil.getChildOfType(decorator, PyCallExpression::class.java) ?: return null
        val callee = PsiTreeUtil.getChildOfType(call, PyReferenceExpression::class.java) ?: return null
        val name = callee.text.substringAfterLast('.')
        val receiver = callee.text.substringBeforeLast('.', "")
        val prefix = apps[receiver] ?: return null
        val arguments = argumentsOf(call)
        val positional = arguments.filter { keyword(it) == null }
        val pathText = positional.firstOrNull()?.text
            ?: arguments.firstNotNullOfOrNull { arg -> keyword(arg)?.takeIf { it.first == "path" || it.first == "rule" }?.second }
        val path = pathText?.let { literal(it, constants) } ?: ""
        val verbs = when {
            name in VERBS -> name.uppercase()
            name in ROUTE_METHODS -> {
                val methods = arguments.firstNotNullOfOrNull { arg -> keyword(arg)?.takeIf { it.first == "methods" }?.second }
                val listed = methods?.let { Regex("""["']([A-Za-z]+)["']""").findAll(it).map { m -> m.groupValues[1].uppercase() }.toList() }
                if (listed.isNullOrEmpty()) "GET" else listed.joinToString("|")
            }
            else -> return null
        }
        return verbs to join(prefix, path)
    }

    private fun join(prefix: String, path: String): String {
        if (prefix == "?" || path == "?") return "?"
        return "/" + listOf(prefix, path).map { it.trim('/') }.filter { it.isNotEmpty() }.joinToString("/")
    }
}
