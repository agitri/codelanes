// PythonCore exposes its PSI names through the experimental PyAst* interfaces; there is no stable alternative.
@file:Suppress("UnstableApiUsage")

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
        class Inline(val path: String, val children: List<Entry>) : Entry
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
        val reached = mutableSetOf<PyFile>()
        for (root in files.filter { it !in included }) expand(root, "", entries, setOf(root), index, found, reached)
        // A urls module nobody reached (a mutual include cycle) counts as a root.
        for (root in files) if (root !in reached) expand(root, "", entries, setOf(root), index, found, reached)
        return found
    }

    private fun expand(
        file: PyFile, prefix: String, entries: Map<PyFile, List<Entry>>, visiting: Set<PyFile>,
        index: ProjectFileIndex, out: MutableList<Pair<String, EntryPoint>>, reached: MutableSet<PyFile>,
    ) {
        reached += file
        expandAll(entries[file].orEmpty(), prefix, file, entries, visiting, index, out, reached)
    }

    private fun expandAll(
        list: List<Entry>, prefix: String, file: PyFile, entries: Map<PyFile, List<Entry>>, visiting: Set<PyFile>,
        index: ProjectFileIndex, out: MutableList<Pair<String, EntryPoint>>, reached: MutableSet<PyFile>,
    ) {
        ProgressManager.checkCanceled()
        for (entry in list) {
            val path = PythonRouteFinder.join(prefix, entry.path())
            when (entry) {
                is Entry.Include -> if (entry.target !in visiting) expand(entry.target, path, entries, visiting + entry.target, index, out, reached)
                is Entry.Inline -> expandAll(entry.children, path, file, entries, visiting, index, out, reached)
                is Entry.View -> viewsOf(entry.view, index).forEach { target ->
                    val virtualFile = target.file.virtualFile ?: return@forEach
                    val short = PythonBlockBuilder.moduleName(target.file).substringAfterLast('.')
                    out += path to EntryPoint("${target.verb} $path → $short::${target.key}", virtualFile.path, target.key)
                }
            }
        }
    }

    private class Target(val verb: String, val key: String, val file: PsiFile)

    private fun Entry.path() = when (this) { is Entry.Include -> path; is Entry.View -> path; is Entry.Inline -> path }

    private fun entriesOf(file: PyFile, byModule: Map<String, List<PyFile>>): List<Entry> {
        val constants = PythonRouteFinder.constantsOf(file)
        val all = byModule.values.flatten()
        val result = mutableListOf<Entry>()
        for (statement in file.statements) {
            if (statement !is PyAssignmentStatement && statement !is PyAugAssignmentStatement) continue
            if (statement.firstChild?.text != "urlpatterns") continue
            result += routesIn(statement, null, constants, byModule, all)
        }
        return result
    }

    /** The path()/re_path() calls under [root] whose nearest enclosing path()/re_path() call is [owner]. */
    private fun routesIn(root: PsiElement, owner: PyCallExpression?, constants: Map<String, String>, byModule: Map<String, List<PyFile>>, all: List<PyFile>): List<Entry> {
        val result = mutableListOf<Entry>()
        for (call in PsiTreeUtil.findChildrenOfType(root, PyCallExpression::class.java)) {
            if (calleeName(call) !in ROUTES) continue
            var parent = PsiTreeUtil.getParentOfType(call, PyCallExpression::class.java)
            while (parent != null && calleeName(parent) !in ROUTES) parent = PsiTreeUtil.getParentOfType(parent, PyCallExpression::class.java)
            if (parent != owner) continue
            val positional = PythonRouteFinder.argumentsOf(call).filter { !Regex("""^\w+\s*=(?!=)""").containsMatchIn(it.text) }
            if (positional.size < 2) continue
            val path = PythonRouteFinder.literal(positional[0].text, constants)
            val view = positional[1]
            val include = view as? PyCallExpression
            if (include != null && calleeName(include) == "include") {
                val target = PythonRouteFinder.argumentsOf(include).firstOrNull() ?: continue
                val text = target.text.trim()
                if (text.startsWith("[") || text.startsWith("(")) {
                    result += Entry.Inline(path, routesIn(target, call, constants, byModule, all))
                } else {
                    val module = if (text.length > 1 && text[0] in "\"'") byModule[text.trim('"', '\'')]?.firstOrNull()
                    else (target as? PyReferenceExpression)?.reference?.resolve() as? PyFile
                    if (module != null && module in all) result += Entry.Include(path, module)
                }
            } else {
                result += Entry.View(path, view)
            }
        }
        return result
    }

    private fun calleeName(call: PyCallExpression): String =
        PsiTreeUtil.getChildOfType(call, PyReferenceExpression::class.java)?.text?.substringAfterLast('.').orEmpty()

    /** Routes for a view expression: `ANY` for a function; for `Cls.as_view()` one per HTTP verb it defines or inherits. */
    private fun viewsOf(view: PyExpression, index: ProjectFileIndex): List<Target> {
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
            call == null && target is PyFunction && target.containingClass == null ->
                listOf(Target("ANY", PythonBlockBuilder.keyOf(target), target.containingFile))
            call != null && target is PyClass -> {
                val own = verbsOf(target)
                val inherited = inheritedVerbs(target, mutableSetOf(target)) - own
                val classKey = PythonBlockBuilder.classKeyOf(target)
                val file = target.containingFile
                when {
                    own.isEmpty() && inherited.isEmpty() -> listOf(Target("ANY", classKey, file))
                    else -> CLASS_VERBS.filter { it in own || it in inherited }.map {
                        if (it in own) Target(it.uppercase(), "${target.name}.$it", file) else Target(it.uppercase(), classKey, file)
                    }
                }
            }
            else -> emptyList()
        }
    }

    private fun verbsOf(cls: PyClass): Set<String> =
        PsiTreeUtil.findChildrenOfType(cls, PyFunction::class.java).filter { it.containingClass == cls }
            .mapNotNull { it.name }.filter { it in CLASS_VERBS }.toSet()

    private fun inheritedVerbs(cls: PyClass, seen: MutableSet<PyClass>): Set<String> {
        val verbs = mutableSetOf<String>()
        for (expression in cls.superClassExpressions) {
            val base = (expression as? PyReferenceExpression)?.reference?.resolve() as? PyClass ?: continue
            if (!seen.add(base)) continue
            verbs += verbsOf(base)
            verbs += inheritedVerbs(base, seen)
        }
        return verbs
    }
}
