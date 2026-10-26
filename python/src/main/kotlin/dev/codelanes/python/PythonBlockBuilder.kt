package dev.codelanes.python

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
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
 * With + calls: functions in other project modules (CALLEE); for an abstract class, its subclasses (IMPLEMENTER).
 */
object PythonBlockBuilder {
    const val HEADER_ID = "header"
    const val MORE_ID = "more:implementers"
    const val MAX_IMPLEMENTERS = 10
    const val MAX_CALLEES = 10
    const val MAX_CHAIN_BLOCKS = 80
    const val MORE_CHAIN_ID = "more:chain"
    const val MAX_IMPLEMENTATIONS = 5

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
        val canvasIds = functionIds + methodIds
        links += calls(canvasIds, context)
        val calls = Calls(file, canvasIds, followedCalls, path, context)
        calls.follow(added)
        blocks += calls.blocks.values.filter { block -> blocks.none { it.id == block.id } }
        links += calls.links
        val implementers = mutableMapOf<String, Block>()
        classes.forEach { cls -> links += implementedBy(cls, classIds, methodIds, implementers, path, context) }
        blocks += implementers.values
        return BuildResult.Supported(BlockModel(blocks, links.distinct()))
    }

    /** Base classes that aren't blocks: they say nothing about what a class builds on. */
    private val NOT_PARENTS = setOf("object", "builtins.object", "typing.Generic", "typing.Protocol", "typing_extensions.Protocol")

    /**
     * The same by how the base is written: with a real SDK, typeshed's `Generic` and `Protocol` are `_SpecialForm`s
     * whose qualified name isn't `typing.Generic`. `ABC` and `object` only when they aren't the project's own class.
     */
    private val NOT_PARENT_NAMES = setOf("Generic", "Protocol")
    private val LIBRARY_NOT_PARENT_NAMES = setOf("ABC", "object")

    /**
     * The left lanes: one block per class the module's classes build on (PARENT) or are given (DEPENDENCY), shared
     * between classes. A class of this module is already on the canvas: it is linked as itself (`class:` id).
     */
    private class Lanes(private val classIds: Map<PyClass, String>, private val context: TypeEvalContext) {
        val blocks = linkedMapOf<String, Block>()
        private val parents = mutableMapOf<String, PyClass>()

        fun bases(cls: PyClass): List<PyClass> {
            val noise = cls.superClassExpressions.flatMap { expression ->
                when (baseName(expression)) {
                    in NOT_PARENT_NAMES -> classesOf(expression, context)
                    in LIBRARY_NOT_PARENT_NAMES -> classesOf(expression, context).filterNot(::inProject)
                    else -> emptyList()
                }
            }
            return cls.getSuperClasses(context).filter { it != cls && it.qualifiedName !in NOT_PARENTS && it !in noise }.distinct()
        }

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
                val parent = parents[id] ?: continue // a class of this module, reached through a parent elsewhere
                for (base in bases(parent)) {
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

    internal fun annotationOf(element: PsiElement): PyExpression? {
        val annotation = PsiTreeUtil.getChildOfType(element, PyAnnotation::class.java)
            ?: element.children.firstNotNullOfOrNull { PsiTreeUtil.getChildOfType(it, PyAnnotation::class.java) }
            ?: return null
        return PsiTreeUtil.getChildOfType(annotation, PyExpression::class.java)
    }

    // Not `cls.decoratorList` / `tuple.elements`: Kotlin binds those to PyAst* types in `intellij.python.ast`.
    private fun isDataclass(cls: PyClass): Boolean = "dataclass" in decoratorNames(cls)

    private fun isModel(cls: PyClass): Boolean =
        cls.superClassExpressions.any { it.text.substringBefore('[').substringAfterLast('.') in setOf("BaseModel", "BaseSettings") }

    /** The classes an annotation names: `Optional[X]`, `X | None`, `list[X]` and `Annotated[X, ...]` all name X. */
    internal fun annotatedClasses(expression: PyExpression, context: TypeEvalContext): List<PyClass> = when (expression) {
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

    /** `Generic` for `typing.Generic[T]`: the name a base is written with. */
    private fun baseName(expression: PyExpression): String =
        expression.text.substringBefore('[').trim().substringAfterLast('.')

    /** The class a base expression names (`Generic[T]` → Generic), or the class of the object it names. */
    private fun classesOf(expression: PyExpression, context: TypeEvalContext): List<PyClass> {
        val target = (expression as? PySubscriptionExpression)?.operand ?: expression
        return listOfNotNull((context.getType(target) as? PyClassType)?.pyClass)
    }

    /**
     * "+ calls", as in JavaBlockBuilder.followCalls: calls into other project modules become CALLEE blocks
     * (`callee:<module>::<key>`), followed as deep as [followed] says, capped at [MAX_CALLEES] per function and
     * [MAX_CHAIN_BLOCKS] in all. A call to an abstract method also shows its project implementations.
     */
    private class Calls(
        private val file: PyFile,
        private val canvasIds: Map<PyFunction, String>,
        private val followed: Set<String>,
        private val path: String,
        private val context: TypeEvalContext,
    ) {
        val blocks = linkedMapOf<String, Block>()
        val links = mutableListOf<Link>()

        fun follow(added: Set<String>) {
            if (followed.isEmpty() && added.isEmpty()) return
            val queue = ArrayDeque(canvasIds.entries.filter { it.value in followed }.map { it.value to it.key })
            val firstRoot = queue.firstOrNull()?.first
            val seen = mutableSetOf<String>()
            var truncated = false
            for (key in added) {
                if (key.endsWith("::class")) {
                    val target = classByQualifiedName(key.removeSuffix("::class")) ?: continue
                    // A class of the open module is already a block of its own.
                    if (target.containingFile == file) continue
                    val id = "callee:$key"
                    blocks.getOrPut(id) { laneBlock(id, BlockKind.CALLEE, target).let { it.copy(collapsed = it.sourceless) } }
                    continue
                }
                val function = functionByKey(key) ?: continue
                // A function of the open module already has a block of its own (Add method… just focuses it).
                if (function.containingFile == file) continue
                val id = calleeId(function)
                blocks.getOrPut(id) { calleeBlock(id, function) }
                if (id in followed) queue += id to function
            }
            while (queue.isNotEmpty()) {
                val (id, caller) = queue.removeFirst()
                if (!seen.add(id)) continue
                if (blocks.size >= MAX_CHAIN_BLOCKS) {
                    truncated = true
                    break
                }
                val allCallees = calledFunctions(caller).filter { it.containingFile != file && isCallee(it) }.distinct()
                val callees = allCallees.take(MAX_CALLEES)
                if (allCallees.size > MAX_CALLEES) {
                    val moreId = "more:calls:$id"
                    blocks[moreId] = Block(moreId, BlockKind.MORE, "and ${allCallees.size - MAX_CALLEES} more calls", path, SourceRange(0, 0), collapsed = true)
                    links += Link(LinkKind.CALLS_INTO, id, moreId)
                }
                for (callee in callees) {
                    if (blocks.size >= MAX_CHAIN_BLOCKS) {
                        truncated = true
                        break
                    }
                    val calleeId = calleeId(callee)
                    blocks.getOrPut(calleeId) { calleeBlock(calleeId, callee) }
                    links += Link(LinkKind.CALLS_INTO, id, calleeId)
                    if (calleeId in followed) queue += calleeId to callee
                    // A call into an abstract method: the real work happens in its implementations.
                    if (isAbstract(callee)) {
                        for (implementation in implementationsOf(callee)) {
                            val own = canvasIds[implementation]
                            if (own != null) {
                                links += Link(LinkKind.IMPLEMENTED_BY, calleeId, own)
                                continue
                            }
                            val implId = calleeId(implementation)
                            blocks.getOrPut(implId) { calleeBlock(implId, implementation) }
                            links += Link(LinkKind.IMPLEMENTED_BY, calleeId, implId)
                            if (implId in followed) queue += implId to implementation
                        }
                    }
                }
            }
            if (truncated && firstRoot != null) {
                blocks[MORE_CHAIN_ID] = Block(
                    MORE_CHAIN_ID, BlockKind.MORE, "chain cut off at $MAX_CHAIN_BLOCKS blocks — use + calls on a block to go further",
                    path, SourceRange(0, 0), collapsed = true,
                )
                links += Link(LinkKind.CALLS_INTO, firstRoot, MORE_CHAIN_ID)
            }
        }

        private fun calledFunctions(caller: PyFunction): List<PyFunction> {
            val resolveContext = PyResolveContext.defaultContext(context)
            return PsiTreeUtil.findChildrenOfType(caller, PyCallExpression::class.java)
                .filter { it !is PyDecorator }
                .flatMap { it.multiResolveCalleeFunction(resolveContext) }
                .filterIsInstance<PyFunction>()
        }

        /** Project implementations of an abstract [method] (at most [MAX_IMPLEMENTATIONS]), as Go to Implementation finds them. */
        private fun implementationsOf(method: PyFunction): List<PyFunction> =
            DefinitionsScopedSearch.search(method, GlobalSearchScope.projectScope(method.project)).findAll().asSequence()
                .filterIsInstance<PyFunction>()
                .filter { it != method && !isAbstract(it) && isCallee(it) }
                .sortedBy(::calleeId)
                .distinctBy(::calleeId)
                .take(MAX_IMPLEMENTATIONS)
                .toList()

        private fun calleeBlock(id: String, function: PyFunction): Block {
            val owner = function.containingClass?.name ?: moduleName(function.containingFile)
            return Block(id, BlockKind.CALLEE, "$owner::${functionTitle(function)}", function.containingFile.virtualFile?.path.orEmpty(), rangeOf(function))
        }

        /** `shop.repo::Repo.save` / `shop.mail::notify` → that function, or null if it no longer exists. */
        private fun functionByKey(key: String): PyFunction? {
            val module = moduleByName(file.project, key.substringBefore("::")) ?: return null
            val name = key.substringAfter("::")
            val function = if ('.' in name) {
                val cls = topLevelClasses(module).firstOrNull { it.name == name.substringBefore('.') } ?: return null
                ownMethods(cls).firstOrNull { it.name == name.substringAfter('.') }
            } else {
                module.statements.filterIsInstance<PyFunction>().firstOrNull { it.name == name }
            }
            return function?.takeIf(::isCallee)
        }

        private fun classByQualifiedName(qualifiedName: String): PyClass? {
            val module = moduleByName(file.project, qualifiedName.substringBeforeLast('.', "")) ?: return null
            return topLevelClasses(module).firstOrNull { it.name == qualifiedName.substringAfterLast('.') }
        }

        private fun topLevelClasses(module: PyFile): List<PyClass> = module.statements.filterIsInstance<PyClass>()
    }

    /** The project module with dotted name [name]: `shop/mail.py` or `shop/mail/__init__.py` for `shop.mail`. */
    fun moduleByName(project: Project, name: String): PyFile? {
        if (name.isEmpty()) return null
        val scope = GlobalSearchScope.projectScope(project)
        val last = name.substringAfterLast('.')
        val candidates = FilenameIndex.getVirtualFilesByName("$last.py", scope) +
            FilenameIndex.getVirtualFilesByName("__init__.py", scope).filter { it.parent?.name == last }
        val psiManager = PsiManager.getInstance(project)
        return candidates.asSequence()
            .mapNotNull { psiManager.findFile(it) as? PyFile }
            .firstOrNull { inProject(it) && moduleName(it) == name }
    }

    private fun calleeId(function: PyFunction): String = "callee:${moduleName(function.containingFile)}::${keyOf(function)}"

    /**
     * A function that can be a block elsewhere: a module function or a method of a top-level class, in a project `.py`
     * file (not a library, not a stub).
     */
    internal fun isCallee(function: PyFunction): Boolean {
        val owner = function.parent
        val addressable = owner is PyFile || owner?.parent.let { it is PyClass && it.parent is PyFile }
        return addressable && isProjectSource(function)
    }

    private fun isProjectSource(element: PsiElement): Boolean =
        inProject(element) && element.containingFile?.virtualFile?.extension == "py"

    /** `@abstractmethod` (and `abc.abstractproperty` & co.), or any method of a Protocol. */
    private fun isAbstract(function: PyFunction): Boolean =
        decoratorNames(function).any { it.startsWith("abstract") } ||
            function.containingClass?.let { cls -> cls.superClassExpressions.any { baseName(it) == "Protocol" } } == true

    /** A class that is meant to be subclassed: an `ABC` or `Protocol` base, `metaclass=ABCMeta`, or an abstract method. */
    private fun isAbstractClass(cls: PyClass): Boolean =
        cls.superClassExpressions.any { baseName(it) in setOf("ABC", "Protocol") || it.text.replace(" ", "").endsWith("=ABCMeta") } ||
            ownMethods(cls).any(::isAbstract)

    /**
     * For an abstract class: its direct project subclasses (as JavaBlockBuilder.implementedBy, at most [MAX_IMPLEMENTERS],
     * alphabetical, plus a "more" block), each with the methods that override one of the class's. A subclass in this
     * module is already a class block: it is linked class to class.
     */
    private fun implementedBy(
        cls: PyClass, classIds: Map<PyClass, String>, methodIds: Map<PyFunction, String>,
        blocks: MutableMap<String, Block>, path: String, context: TypeEvalContext,
    ): List<Link> {
        if (!isAbstractClass(cls)) return emptyList()
        val classId = classIds.getValue(cls)
        val links = mutableListOf<Link>()
        val subclasses = DefinitionsScopedSearch.search(cls, GlobalSearchScope.projectScope(cls.project)).findAll()
            .filterIsInstance<PyClass>()
            .filter { it != cls && it.parent is PyFile && isProjectSource(it) && cls in it.getSuperClasses(context) }
            .distinctBy { it.qualifiedName ?: it.name }
        subclasses.mapNotNull { classIds[it] }.forEach { links += Link(LinkKind.IMPLEMENTED_BY, classId, it) }
        val candidates = subclasses.filter { it !in classIds }.sortedWith(compareBy({ it.name }, { it.qualifiedName }))
        val methods = ownMethods(cls)
        for (implementer in candidates.take(MAX_IMPLEMENTERS)) {
            val implemented = methods.mapNotNull { method -> implementer.findMethodByName(method.name ?: return@mapNotNull null, false, context) }
            val block = laneBlock("implementer:${implementer.qualifiedName ?: implementer.name}", BlockKind.IMPLEMENTER, implementer)
                .copy(summary = implemented.distinct().map(::functionTitle))
            blocks.getOrPut(block.id) { block }
            links += Link(LinkKind.IMPLEMENTED_BY, classId, block.id)
            for (method in methods) {
                val implementation = implementer.findMethodByName(method.name ?: continue, false, context) ?: continue
                val id = "implementation:${moduleName(implementation.containingFile)}::${keyOf(implementation)}"
                blocks.getOrPut(id) {
                    Block(id, BlockKind.IMPLEMENTATION, "${implementer.name}::${functionTitle(implementation)}",
                        implementation.containingFile.virtualFile?.path.orEmpty(), rangeOf(implementation), collapsed = true)
                }
                links += Link(LinkKind.OWNS, block.id, id)
                links += Link(LinkKind.OVERRIDES, id, methodIds.getValue(method), rangeOf(method))
            }
        }
        val rest = candidates.drop(MAX_IMPLEMENTERS)
        if (rest.isNotEmpty()) {
            val moreId = if (MORE_ID in blocks) "$MORE_ID:$classId" else MORE_ID
            blocks[moreId] = Block(moreId, BlockKind.MORE, "and ${rest.size} more", path, SourceRange(0, 0), collapsed = true, summary = rest.mapNotNull { it.name })
            links += Link(LinkKind.IMPLEMENTED_BY, classId, moreId)
        }
        return links
    }

    internal fun decoratorNames(owner: PsiElement): List<String> =
        PsiTreeUtil.getChildOfType(owner, PyDecoratorList::class.java)
            ?.let { PsiTreeUtil.getChildrenOfTypeAsList(it, PyDecorator::class.java) }.orEmpty()
            .map { it.text.removePrefix("@").substringBefore('(').trim().substringAfterLast('.') }

    internal fun ownMethods(cls: PyClass): List<PyFunction> = cls.statementList.statements.filterIsInstance<PyFunction>()

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

    /** The functions that get a METHOD block of their own: module-level defs, then each top-level class's own methods. */
    fun canvasFunctions(file: PyFile): List<PyFunction> =
        file.statements.filterIsInstance<PyFunction>() + file.statements.filterIsInstance<PyClass>().flatMap(::ownMethods)

    /** The key of a top-level class in its block id `class:<module>.<key>`: its name, `#n` when redefined. */
    fun classKeyOf(cls: PyClass): String {
        val module = cls.containingFile as? PyFile ?: return cls.name.orEmpty()
        return suffixed(module.statements.filterIsInstance<PyClass>()) { it.name.orEmpty() }[cls] ?: cls.name.orEmpty()
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
