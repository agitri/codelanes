package dev.codelanes.python

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.PythonFileType
import com.jetbrains.python.psi.PyArgumentList
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyDecorator
import com.jetbrains.python.psi.PyDecoratorList
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyTargetExpression
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

    /** How often a decorator's receiver was resolved through imports (tests check that only route decorators are). */
    @Volatile var importedLookups = 0

    /** Where route and urls modules are looked for: the project's Python files. */
    fun searchScope(project: Project): GlobalSearchScope =
        GlobalSearchScope.getScopeRestrictedByFileTypes(GlobalSearchScope.projectScope(project), PythonFileType.INSTANCE)

    /** One find()'s module-level reads, so a module imported by many route files is read once. */
    private class Cache {
        val apps = HashMap<PyFile, Map<String, String>>()
        val constants = HashMap<PyFile, Map<String, String>>()
    }

    /** Needs indexes and a read action; checks for cancellation. Sorted by path, then label. */
    fun find(project: Project): List<EntryPoint> {
        val files = ProjectFileIndex.getInstance(project)
        val candidates = linkedSetOf<PsiFile>()
        // Only files that mention a route decorator or a framework class: no need to parse the rest of the project.
        val search = PsiSearchHelper.getInstance(project)
        val words = VERBS + ROUTE_METHODS + "route" + APP_CLASSES.keys
        val scope = searchScope(project)
        for (word in words) {
            search.processAllFilesWithWord(word, scope, { candidates += it; true }, true)
        }
        val cache = Cache()
        val found = mutableListOf<Pair<String, EntryPoint>>()
        for (candidate in candidates) {
            ProgressManager.checkCanceled()
            val file = candidate as? PyFile ?: continue
            val virtualFile = file.virtualFile ?: continue
            if (virtualFile.extension != "py" || files.isInLibrary(virtualFile)) continue
            val apps = appsOf(file, cache)
            val constants = constantsOf(file, cache)
            val short = PythonBlockBuilder.moduleName(file).substringAfterLast('.')
            for (function in PythonBlockBuilder.canvasFunctions(file)) {
                for (decorator in decoratorsOf(function)) {
                    val route = routeOf(decorator, apps, constants, files, cache) ?: continue
                    val key = PythonBlockBuilder.keyOf(function)
                    found += route.second to EntryPoint("${route.first} ${route.second} → $short::$key", virtualFile.path, key)
                }
            }
        }
        DjangoUrls.find(project, files).forEach { found += it }
        return found.distinctBy { it.second.label }.sortedWith(compareBy({ it.first }, { it.second.label })).map { it.second }
    }

    private fun decoratorsOf(function: PyFunction): List<PyDecorator> =
        PsiTreeUtil.getChildOfType(function, PyDecoratorList::class.java)
            ?.let { PsiTreeUtil.getChildrenOfTypeAsList(it, PyDecorator::class.java) }.orEmpty()

    internal fun argumentsOf(call: PyCallExpression): List<PyExpression> =
        PsiTreeUtil.getChildOfType(call, PyArgumentList::class.java)
            ?.let { PsiTreeUtil.getChildrenOfTypeAsList(it, PyExpression::class.java) }.orEmpty()

    /** `name=value` → (name, value text); anything else → null. */
    private fun keyword(argument: PyExpression): Pair<String, String>? {
        val match = Regex("""^(\w+)\s*=(?!=)\s*(.*)$""", RegexOption.DOT_MATCHES_ALL).find(argument.text) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun callOf(expression: PyExpression?): PyCallExpression? = expression as? PyCallExpression

    /** Module-level `name = FastAPI(...)` etc. → name to its route prefix ("" or "?"). */
    private fun appsOf(file: PyFile, cache: Cache): Map<String, String> = cache.apps.getOrPut(file) {
        val apps = mutableMapOf<String, String>()
        val constants = constantsOf(file, cache)
        for (statement in file.statements.filterIsInstance<PyAssignmentStatement>()) {
            val parts = PsiTreeUtil.getChildrenOfTypeAsList(statement, PyExpression::class.java)
            if (parts.size < 2) continue
            val call = callOf(parts.last()) ?: continue
            val className = call.firstChild.text.substringAfterLast('.').trim()
            if (className !in APP_CLASSES) continue
            val prefixKeyword = APP_CLASSES[className]
            val prefix = argumentsOf(call).firstNotNullOfOrNull { arg -> keyword(arg)?.takeIf { it.first == prefixKeyword } }
                ?.let { literal(it.second, constants) } ?: ""
            apps[parts[0].text] = prefix
        }
        apps
    }

    private fun constantsOf(file: PyFile, cache: Cache): Map<String, String> = cache.constants.getOrPut(file) { constantsOf(file) }

    /** Module-level `NAME = "literal"` assignments. */
    internal fun constantsOf(file: PyFile): Map<String, String> {
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
    internal fun literal(text: String, constants: Map<String, String>): String {
        val trimmed = text.trim()
        constants[trimmed]?.let { return it }
        val match = STRING.matchEntire(trimmed) ?: return "?"
        return if (match.groupValues[1].lowercase() == "b") "?" else match.groupValues[3]
    }

    /** (verbs joined by `|`, full path) for a route decorator, or null. */
    private fun routeOf(
        decorator: PyDecorator, apps: Map<String, String>, constants: Map<String, String>, files: ProjectFileIndex, cache: Cache,
    ): Pair<String, String>? {
        val call = PsiTreeUtil.getChildOfType(decorator, PyCallExpression::class.java) ?: return null
        val callee = PsiTreeUtil.getChildOfType(call, PyReferenceExpression::class.java) ?: return null
        val name = callee.text.substringAfterLast('.')
        // Cheap name check first: `@pytest.mark.parametrize(...)` and the like never resolve anything.
        if (name !in VERBS && name !in ROUTE_METHODS) return null
        val receiver = callee.text.substringBeforeLast('.', "")
        val prefix = apps[receiver] ?: importedPrefix(callee, receiver, files, cache) ?: return null
        val arguments = argumentsOf(call)
        val positional = arguments.filter { keyword(it) == null }
        val pathText = positional.firstOrNull()?.text
            ?: arguments.firstNotNullOfOrNull { arg -> keyword(arg)?.takeIf { it.first == "path" || it.first == "rule" }?.second }
        val path = pathText?.let { literal(it, constants) } ?: ""
        val verbs = when (name) {
            in VERBS -> name.uppercase()
            in ROUTE_METHODS -> {
                val methods = arguments.firstNotNullOfOrNull { arg -> keyword(arg)?.takeIf { it.first == "methods" }?.second }
                val listed = methods?.let { Regex("""["']([A-Za-z]+)["']""").findAll(it).map { m -> m.groupValues[1].uppercase() }.toList() }
                when {
                    methods == null -> "GET"
                    methods.trim().firstOrNull() !in listOf('[', '(', '{') -> "?"
                    listed.isNullOrEmpty() -> "GET"
                    else -> listed.joinToString("|")
                }
            }
            else -> return null
        }
        return verbs to join(prefix, path)
    }

    /** The receiver `name` of `name.get(...)` imported from another project module: its assignment there, one hop. */
    private fun importedPrefix(callee: PyReferenceExpression, receiver: String, files: ProjectFileIndex, cache: Cache): String? {
        if (receiver.isEmpty()) return null
        importedLookups++
        val qualifier = PsiTreeUtil.getChildOfType(callee, PyReferenceExpression::class.java) ?: return null
        val target = qualifier.reference?.resolve() as? PyTargetExpression ?: return null
        val file = target.containingFile as? PyFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (!files.isInContent(virtualFile) || files.isInLibrary(virtualFile)) return null
        return appsOf(file, cache)[target.text]
    }

    /** Keeps the path as written (a trailing slash stays), without double slashes. */
    internal fun join(prefix: String, path: String): String {
        if (prefix == "?" || path == "?") return "?"
        val rest = path.trimStart('/')
        val head = if (rest.isEmpty()) prefix.trimStart('/') else prefix.trim('/')
        return "/" + listOf(head, rest).filter { it.isNotEmpty() }.joinToString("/")
    }
}
