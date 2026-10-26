package dev.codelanes.python

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyAnnotation
import com.jetbrains.python.psi.PyAssignmentStatement
import com.jetbrains.python.psi.PyBinaryExpression
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyDecorator
import com.jetbrains.python.psi.PyDecoratorList
import com.jetbrains.python.psi.PyExpression
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyParameterList
import com.jetbrains.python.psi.PyParenthesizedExpression
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PySubscriptionExpression
import com.jetbrains.python.psi.PyTupleExpression
import com.jetbrains.python.psi.PyTypeDeclarationStatement
import com.jetbrains.python.psi.resolve.PyResolveContext
import com.jetbrains.python.psi.resolve.QualifiedNameFinder
import com.jetbrains.python.psi.types.PyClassType
import com.jetbrains.python.psi.types.TypeEvalContext
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
 * Left of the classes: their bases (PARENT) and injected types (DEPENDENCY); lines for overrides and calls.
 */
object PythonBlockBuilder {
    const val HEADER_ID = "header"

    @Suppress("UNUSED_PARAMETER") // followedCalls/added: callees come later.
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
        val functionIds = ids(functions, null)
        val moduleExcluded = listOfNotNull(header?.range) + (classes + functions).map(::rangeOf)
        blocks += Block(moduleId, BlockKind.MODULE, "module $module", path, SourceRange(0, file.textLength),
            excluded = moduleExcluded.sortedBy { it.start })
        header?.let { blocks += it }
        functions.forEach { function ->
            val id = functionIds.getValue(function)
            blocks += Block(id, BlockKind.METHOD, functionTitle(function), path, rangeOf(function))
            links += Link(LinkKind.OWNS, moduleId, id)
        }
        val classKeys = suffixed(classes) { it.name.orEmpty() }
        val classIds = classes.associateWith { "class:$module.${classKeys.getValue(it)}" }
        val methodIds = mutableMapOf<PyFunction, String>()
        classes.forEach { cls ->
            val classId = classIds.getValue(cls)
            val methods = ownMethods(cls)
            val own = ids(methods, classKeys.getValue(cls))
            methodIds += own
            blocks += Block(classId, BlockKind.CLASS, classTitle(cls), path, rangeOf(cls), excluded = methods.map(::rangeOf))
            methods.forEach { method ->
                val id = own.getValue(method)
                blocks += Block(id, BlockKind.METHOD, functionTitle(method), path, rangeOf(method))
                links += Link(LinkKind.OWNS, classId, id)
            }
        }
        val context = TypeEvalContexts.codeAnalysis(file.project, file)
        val lanes = Lanes(classIds, context)
        classes.forEach { cls ->
            val classId = classIds.getValue(cls)
            val bases = lanes.bases(cls)
            bases.forEach { links += Link(LinkKind.EXTENDS, lanes.idOf(it, BlockKind.PARENT), classId) }
            ownMethods(cls).forEach { method -> links += overrides(methodIds.getValue(method), method, bases, lanes, context) }
            dependencies(cls, context).forEach { links += Link(LinkKind.INJECTS, lanes.idOf(it, BlockKind.DEPENDENCY), classId) }
        }
        links += lanes.revealDeeper(revealed)
        blocks += lanes.blocks.values
        links += calls(functionIds + methodIds, context)
        return BuildResult.Supported(BlockModel(blocks, links.distinct()))
    }

    /** Base classes that aren't blocks: they say nothing about what a class builds on. */
    private val NOT_PARENTS = setOf("object", "builtins.object", "typing.Generic", "typing.Protocol", "typing_extensions.Protocol")

    /**
     * The left lanes: one block per class the module's classes build on (PARENT) or are given (DEPENDENCY), shared
     * between classes. A class of this module is already on the canvas: it is linked as itself (`class:` id).
     */
    private class Lanes(private val classIds: Map<PyClass, String>, private val context: TypeEvalContext) {
        val blocks = linkedMapOf<String, Block>()
        private val parents = mutableMapOf<String, PyClass>()

        fun bases(cls: PyClass): List<PyClass> =
            cls.getSuperClasses(context).filter { it != cls && it.qualifiedName !in NOT_PARENTS }.distinct()

        fun idOf(cls: PyClass, kind: BlockKind): String {
            classIds[cls]?.let { return it }
            val id = "${kind.name.lowercase()}:${cls.qualifiedName ?: cls.name}"
            blocks.getOrPut(id) { laneBlock(id, kind, cls) }
            if (kind == BlockKind.PARENT) parents[id] = cls
            return id
        }

        /** As JavaBlockBuilder.revealDeeper: the bases of revealed parent blocks, as deep as revealed. */
        fun revealDeeper(revealed: Set<String>): List<Link> {
            val queue = ArrayDeque(revealed.filter { it in parents })
            val seen = mutableSetOf<String>()
            val links = mutableListOf<Link>()
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!seen.add(id)) continue
                for (base in bases(parents.getValue(id))) {
                    val baseId = idOf(base, BlockKind.PARENT)
                    links += Link(LinkKind.EXTENDS, baseId, id)
                    if (baseId in revealed) queue += baseId
                }
            }
            return links
        }
    }

    /** A collapsed block for a class elsewhere: its source when it's project code, else its name and methods only. */
    private fun laneBlock(id: String, kind: BlockKind, cls: PyClass): Block {
        val sourceless = !inProject(cls)
        return Block(
            id = id, kind = kind, title = "class ${cls.name}",
            filePath = cls.containingFile?.virtualFile?.path.orEmpty(),
            range = if (sourceless) SourceRange(0, 0) else rangeOf(cls),
            collapsed = true,
            summary = ownMethods(cls).map(::functionTitle),
            sourceless = sourceless,
        )
    }

    /** [method] links to each base (on the canvas) that defines a method with the same name. */
    private fun overrides(methodId: String, method: PyFunction, bases: List<PyClass>, lanes: Lanes, context: TypeEvalContext): List<Link> {
        val name = method.name ?: return emptyList()
        return bases.mapNotNull { base ->
            val overridden = base.findMethodByName(name, false, context) ?: return@mapNotNull null
            val targetId = lanes.idOf(base, BlockKind.PARENT)
            val landing = if (inProject(base)) rangeOf(overridden) else null
            Link(LinkKind.OVERRIDES, methodId, targetId, landing)
        }
    }

    /**
     * Injected types: the annotated parameters of `__init__`, and for a dataclass, a pydantic-style model or a class
     * without `__init__` its annotated class attributes. Only classes in the project's own code count.
     */
    private fun dependencies(cls: PyClass, context: TypeEvalContext): List<PyClass> {
        val init = ownMethods(cls).firstOrNull { it.name == "__init__" }
        val annotations = mutableListOf<PyExpression>()
        init?.let { function ->
            PsiTreeUtil.getChildOfType(function, PyParameterList::class.java)?.parameters.orEmpty()
                .mapNotNullTo(annotations) { annotationOf(it) }
        }
        if (init == null || isDataclass(cls) || isModel(cls)) {
            cls.statementList.statements
                .filter { it is PyTypeDeclarationStatement || it is PyAssignmentStatement }
                .mapNotNullTo(annotations) { annotationOf(it) }
        }
        return annotations.flatMap { annotatedClasses(it, context) }
            .filter { it != cls && inProject(it) }
            .distinct()
    }

    private fun annotationOf(element: PsiElement): PyExpression? {
        val annotation = PsiTreeUtil.getChildOfType(element, PyAnnotation::class.java)
            ?: element.children.firstNotNullOfOrNull { PsiTreeUtil.getChildOfType(it, PyAnnotation::class.java) }
            ?: return null
        return PsiTreeUtil.getChildOfType(annotation, PyExpression::class.java)
    }

    private fun isDataclass(cls: PyClass): Boolean =
        // Not `cls.decoratorList` / `tuple.elements`: Kotlin binds those to PyAst* types in `intellij.python.ast`.
        PsiTreeUtil.getChildOfType(cls, PyDecoratorList::class.java)
            ?.let { PsiTreeUtil.getChildrenOfTypeAsList(it, PyDecorator::class.java) }.orEmpty().any { decorator ->
            decorator.text.removePrefix("@").substringBefore('(').trim().substringAfterLast('.') == "dataclass"
        }

    private fun isModel(cls: PyClass): Boolean =
        cls.superClassExpressions.any { it.text.substringBefore('[').substringAfterLast('.') in setOf("BaseModel", "BaseSettings") }

    /** The classes an annotation names: `Optional[X]`, `X | None`, `list[X]` and `Annotated[X, ...]` all name X. */
    private fun annotatedClasses(expression: PyExpression, context: TypeEvalContext): List<PyClass> = when (expression) {
        is PySubscriptionExpression -> {
            val operand = expression.operand
            val index = expression.indexExpression
            val arguments = (index as? PyTupleExpression)?.let(::elementsOf) ?: listOfNotNull(index)
            when (operand.text.substringAfterLast('.')) {
                "Annotated" -> arguments.take(1).flatMap { annotatedClasses(it, context) }
                "ClassVar" -> emptyList()
                else -> annotatedClasses(operand, context) + arguments.flatMap { annotatedClasses(it, context) }
            }
        }
        is PyBinaryExpression -> listOfNotNull(expression.leftExpression, expression.rightExpression).flatMap { annotatedClasses(it, context) }
        is PyParenthesizedExpression -> expression.containedExpression?.let { annotatedClasses(it, context) }.orEmpty()
        is PyTupleExpression -> elementsOf(expression).flatMap { annotatedClasses(it, context) }
        is PyReferenceExpression -> listOfNotNull((context.getType(expression) as? PyClassType)?.takeIf { it.isDefinition }?.pyClass)
        else -> emptyList()
    }

    private fun elementsOf(tuple: PyTupleExpression): List<PyExpression> =
        PsiTreeUtil.getChildrenOfTypeAsList(tuple, PyExpression::class.java)

    /** Calls inside a function or method that resolve to another function or method of this module. */
    private fun calls(ids: Map<PyFunction, String>, context: TypeEvalContext): List<Link> {
        val resolveContext = PyResolveContext.defaultContext(context)
        return ids.flatMap { (caller, callerId) ->
            PsiTreeUtil.findChildrenOfType(caller, PyCallExpression::class.java)
                .filter { it !is PyDecorator }
                .flatMap { it.multiResolveCalleeFunction(resolveContext) }
                .mapNotNull { ids[it] }
                .filter { it != callerId }
                .distinct()
                .map { Link(LinkKind.CALLS, callerId, it) }
        }
    }

    private fun ownMethods(cls: PyClass): List<PyFunction> = cls.statementList.statements.filterIsInstance<PyFunction>()

    /** Project code, not a library (site-packages, the standard library). */
    private fun inProject(element: PsiElement): Boolean {
        val file = element.containingFile?.virtualFile ?: return false
        val index = ProjectFileIndex.getInstance(element.project)
        return index.isInContent(file) && !index.isInLibrary(file)
    }

    /**
     * Python allows redefining a name (a property and its setter, `@overload`s, a class defined twice): the second and
     * later definitions get `#2`, `#3`, ... so every definition keeps its own block.
     */
    private fun <T : Any> suffixed(elements: List<T>, key: (T) -> String): Map<T, String> {
        val seen = mutableMapOf<String, Int>()
        return elements.associateWith { element ->
            val k = key(element)
            val n = seen.merge(k, 1, Int::plus)!!
            if (n == 1) k else "$k#$n"
        }
    }

    /** `method:<name>` per module function, `method:<classKey>.<name>` per method ([classKey] carries the class's suffix). */
    private fun ids(functions: List<PyFunction>, classKey: String?): Map<PyFunction, String> =
        suffixed(functions) { f -> classKey?.let { "$it.${f.name}" } ?: f.name.orEmpty() }.mapValues { "method:${it.value}" }

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
