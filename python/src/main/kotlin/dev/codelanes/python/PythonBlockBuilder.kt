package dev.codelanes.python

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyParameterList
import com.jetbrains.python.psi.resolve.QualifiedNameFinder
import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
import dev.codelanes.model.SourceRange

/**
 * Turns a Python module into a [BlockModel]: the leading imports (HEADER), the module's own statements (MODULE),
 * each top-level class (CLASS, minus its methods) and each top-level function and method (METHOD).
 * Only direct children become blocks: nested defs and classes, and defs inside `if`/`try` blocks, stay in their parent.
 */
object PythonBlockBuilder {
    const val HEADER_ID = "header"

    @Suppress("UNUSED_PARAMETER") // revealed/followedCalls/added: parents, calls and callees come later.
    fun build(file: PyFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult {
        val path = file.virtualFile?.path.orEmpty()
        val module = moduleName(file)
        val moduleId = "module:$module"
        val functions = file.statements.filterIsInstance<PyFunction>()
        val classes = file.statements.filterIsInstance<PyClass>()
        val imports = file.importBlock

        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        val header = if (imports.isEmpty()) null else
            Block(HEADER_ID, BlockKind.HEADER, "${imports.size} imports", path,
                SourceRange(imports.first().textRange.startOffset, imports.last().textRange.endOffset), collapsed = true)
        val functionIds = ids(functions)
        val moduleExcluded = listOfNotNull(header?.range) + (classes + functions).map(::rangeOf)
        blocks += Block(moduleId, BlockKind.MODULE, "module $module", path, SourceRange(0, file.textLength),
            excluded = moduleExcluded.sortedBy { it.start })
        header?.let { blocks += it }
        functions.forEach { function ->
            val id = functionIds.getValue(function)
            blocks += Block(id, BlockKind.METHOD, functionTitle(function), path, rangeOf(function))
            links += Link(LinkKind.OWNS, moduleId, id)
        }
        classes.forEach { cls ->
            val classId = "class:${cls.qualifiedName ?: "$module.${cls.name}"}"
            val methods = cls.statementList.statements.filterIsInstance<PyFunction>()
            val methodIds = ids(methods)
            blocks += Block(classId, BlockKind.CLASS, classTitle(cls), path, rangeOf(cls), excluded = methods.map(::rangeOf))
            methods.forEach { method ->
                val id = methodIds.getValue(method)
                blocks += Block(id, BlockKind.METHOD, functionTitle(method), path, rangeOf(method))
                links += Link(LinkKind.OWNS, classId, id)
            }
        }
        return BuildResult.Supported(BlockModel(blocks, links))
    }

    /**
     * `method:<key>` per function. Python allows redefining a name (a property and its setter, `@overload`s): the
     * second and later definitions get `#2`, `#3`, ... so every definition keeps its own block.
     */
    private fun ids(functions: List<PyFunction>): Map<PyFunction, String> {
        val seen = mutableMapOf<String, Int>()
        return functions.associateWith { function ->
            val key = keyOf(function)
            val n = seen.merge(key, 1, Int::plus)!!
            if (n == 1) "method:$key" else "method:$key#$n"
        }
    }

    /** The dotted name of [file] from its source root (`shop/orders/views.py` → `shop.orders.views`, `__init__.py` → its package). */
    fun moduleName(file: PsiFile): String {
        val virtualFile = file.virtualFile ?: file.originalFile.virtualFile
        val name = virtualFile?.let { QualifiedNameFinder.findShortestImportableName(file, it) }
        return if (name.isNullOrEmpty()) file.name.removeSuffix(".py") else name
    }

    /** `name` for a module-level function, `Class.name` for a method. */
    fun keyOf(function: PyFunction): String =
        function.containingClass?.let { "${it.name}.${function.name}" } ?: function.name.orEmpty()

    /** `def name(params)` / `async def name(params)`, the parameters as written (on one line). */
    fun functionTitle(function: PyFunction): String {
        // Not `function.parameterList`: Kotlin binds that to PyAstFunction's, whose type lives in `intellij.python.ast`.
        val written = PsiTreeUtil.getChildOfType(function, PyParameterList::class.java)?.text ?: "()"
        val params = written.replace(Regex("\\s+"), " ")
            .replace("( ", "(").replace(Regex(",? \\)$"), ")")
        return "${if (function.isAsync) "async " else ""}def ${function.name}$params"
    }

    private fun classTitle(cls: PyClass): String =
        "class ${cls.name}" + (cls.superClassExpressionList?.text?.replace(Regex("\\s+"), " ") ?: "")

    /** Python PSI ranges of a def or class include its decorators. */
    private fun rangeOf(element: PsiElement): SourceRange = SourceRange(element.textRange.startOffset, element.textRange.endOffset)
}
