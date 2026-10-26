package dev.codelanes.python

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyAugAssignmentStatement
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyReferenceExpression
import dev.codelanes.lang.EntryPoint

/**
 * Django `urlpatterns`: `path()` / `re_path()` (matched by name) to a function view (`ANY`) or `SomeView.as_view()`
 * (one route per HTTP method the class defines). `include("app.urls")` / `include(urls_module)` is followed with its
 * prefix; a urls module that another one includes is only listed through that include. Views resolve through imports;
 * one that doesn't is skipped. Paths are shown as written.
 */
internal object DjangoUrls {
    private val CLASS_VERBS = listOf("get", "post", "put", "patch", "delete")
    private val ROUTES = setOf("path", "re_path")

    private sealed interface Entry {
        class Include(val path: String, val target: PyFile) : Entry
        class View(val path: String, val view: PyExpression) : Entry
    }

    /** Pairs of (path, entry point), unsorted. */
    fun find(project: Project, index: ProjectFileIndex): List<Pair<String, EntryPoint>> {
        val files = linkedSetOf<PyFile>()
        PsiSearchHelper.getInstance(project).processAllFilesWithWord("urlpatterns", GlobalSearchScope.projectScope(project), {
            val file = it as? PyFile
            val virtualFile = file?.virtualFile
            if (file != null && virtualFile != null && virtualFile.extension == "py" && !index.isInLibrary(virtualFile)) files += file
            true
        }, true)
        val byModule = files.groupBy { PythonBlockBuilder.moduleName(it) }
        val entries = files.associateWith { entriesOf(it, byModule) }
        val included = entries.values.flatten().filterIsInstance<Entry.Include>().map { it.target }.toSet()
        val found = mutableListOf<Pair<String, EntryPoint>>()
        for (root in files.filter { it !in included }) expand(root, "", entries, setOf(root), index, found)
        return found
    }

    private fun expand(
        file: PyFile, prefix: String, entries: Map<PyFile, List<Entry>>, visiting: Set<PyFile>,
        index: ProjectFileIndex, out: MutableList<Pair<String, EntryPoint>>,
    ) {
        ProgressManager.checkCanceled()
        for (entry in entries[file].orEmpty()) {
            val path = PythonRouteFinder.join(prefix, entry.path())
            when (entry) {
                is Entry.Include -> if (entry.target !in visiting) expand(entry.target, path, entries, visiting + entry.target, index, out)
                is Entry.View -> viewsOf(entry.view, index).forEach { (verb, function) ->
                    val virtualFile = function.containingFile.virtualFile ?: return@forEach
                    val key = PythonBlockBuilder.keyOf(function)
                    val short = PythonBlockBuilder.moduleName(function.containingFile).substringAfterLast('.')
                    out += path to EntryPoint("$verb $path → $short::$key", virtualFile.path, key)
                }
            }
        }
    }

    private fun Entry.path() = when (this) { is Entry.Include -> path; is Entry.View -> path }

    private fun entriesOf(file: PyFile, byModule: Map<String, List<PyFile>>): List<Entry> {
        val constants = PythonRouteFinder.constantsOf(file)
        val result = mutableListOf<Entry>()
        for (statement in file.statements) {
            if (statement !is PyAssignmentStatement && statement !is PyAugAssignmentStatement) continue
            if (statement.firstChild?.text != "urlpatterns") continue
            for (call in PsiTreeUtil.findChildrenOfType(statement, PyCallExpression::class.java)) {
                if (calleeName(call) !in ROUTES) continue
                if (PsiTreeUtil.getParentOfType(call, PyCallExpression::class.java).let { it != null && calleeName(it) in ROUTES }) continue
                val arguments = PythonRouteFinder.argumentsOf(call)
                val positional = arguments.filter { !Regex("""^\w+\s*=(?!=)""").containsMatchIn(it.text) }
                if (positional.size < 2) continue
                val path = PythonRouteFinder.literal(positional[0].text, constants)
                val view = positional[1]
                val include = view as? PyCallExpression
                if (include != null && calleeName(include) == "include") {
                    val target = PythonRouteFinder.argumentsOf(include).firstOrNull() ?: continue
                    val text = target.text.trim()
                    val module = if (text.length > 1 && text[0] in "\"'") byModule[text.trim('"', '\'')]?.firstOrNull()
                    else (target as? PyReferenceExpression)?.reference?.resolve() as? PyFile
                    if (module != null && module in byModule.values.flatten()) result += Entry.Include(path, module)
                } else {
                    result += Entry.View(path, view)
                }
            }
        }
        return result
    }

    private fun calleeName(call: PyCallExpression): String =
        PsiTreeUtil.getChildOfType(call, PyReferenceExpression::class.java)?.text?.substringAfterLast('.').orEmpty()

    /** (verb, function) pairs for a view expression: `ANY` for a function, one per defined verb for `Cls.as_view()`. */
    private fun viewsOf(view: PyExpression, index: ProjectFileIndex): List<Pair<String, PyFunction>> {
        val call = view as? PyCallExpression
        val reference = if (call != null) {
            if (calleeName(call) != "as_view") return emptyList()
            val callee = PsiTreeUtil.getChildOfType(call, PyReferenceExpression::class.java)
            PsiTreeUtil.getChildOfType(callee, PyReferenceExpression::class.java)
        } else view as? PyReferenceExpression
        val target: PsiElement = reference?.reference?.resolve() ?: return emptyList()
        val virtualFile = target.containingFile?.virtualFile ?: return emptyList()
        if (!index.isInContent(virtualFile) || index.isInLibrary(virtualFile)) return emptyList()
        return when {
            call == null && target is PyFunction && target.containingClass == null -> listOf("ANY" to target)
            call != null && target is PyClass -> {
                val methods = PsiTreeUtil.findChildrenOfType(target, PyFunction::class.java).filter { it.containingClass == target }
                CLASS_VERBS.mapNotNull { verb -> methods.firstOrNull { it.name == verb }?.let { verb.uppercase() to it } }
            }
            else -> emptyList()
        }
    }
}
