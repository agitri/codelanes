package dev.codelanes.kotlin

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifier
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.util.PsiTreeUtil
import dev.codelanes.java.JavaBlockBuilder
import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
import dev.codelanes.model.SourceRange
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtDelegatedSuperTypeEntry
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration

/**
 * Turns a Kotlin file into a [BlockModel], laid out as Python's module canvas: the package and imports (HEADER),
 * the file's other top-level declarations (MODULE, only when there are any), each top-level class, interface or
 * object (CLASS, minus its member functions) and each top-level and member function (METHOD).
 * Only direct children become blocks: nested classes, local functions, properties and `init` blocks stay in their parent.
 * This builder covers the structure only; parents, dependencies, calls and overrides are layered on by later tasks.
 */
object KotlinBlockBuilder {
    const val HEADER_ID = "header"
    const val MORE_ID = "more:implementers"
    const val MAX_IMPLEMENTERS = 10
    const val MAX_CALLEES = 10
    const val MAX_CHAIN_BLOCKS = 80
    const val MORE_CHAIN_ID = "more:chain"
    const val MAX_IMPLEMENTATIONS = 5

    fun build(file: KtFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult {
        val path = file.virtualFile?.path.orEmpty()
        val module = moduleFqn(file)
        val moduleId = "module:$module"
        val declarations = file.declarations
        val classes = declarations.filterIsInstance<KtClassOrObject>()
        val functions = declarations.filterIsInstance<KtNamedFunction>().filter { it.name != null }
        // A class-only file has no MODULE block (the Java-like case); a file with nothing else at all keeps one.
        val hasModuleContent = declarations.any { it !is KtClassOrObject } || file.annotationEntries.isNotEmpty() || declarations.isEmpty()

        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        val header = headerOf(file, path)
        header?.let { blocks += it }
        if (hasModuleContent) {
            val excluded = listOfNotNull(header?.range) + (classes + functions).map(::rangeOf)
            blocks += Block(moduleId, BlockKind.MODULE, "module $module", path, SourceRange(0, file.textLength),
                excluded = excluded.sortedBy { it.start })
        }
        val methodIds = mutableMapOf<KtNamedFunction, String>()
        functions.forEach { function ->
            val id = "method:${functionKey(function)}"
            methodIds[function] = id
            blocks += Block(id, BlockKind.METHOD, functionTitle(function), path, rangeOf(function))
            if (hasModuleContent) links += Link(LinkKind.OWNS, moduleId, id)
        }
        val classIds = mutableMapOf<KtClassOrObject, String>()
        classes.forEach { cls ->
            val classId = "class:${cls.fqName?.asString() ?: cls.name.orEmpty()}"
            classIds[cls] = classId
            val members = memberFunctions(cls)
            blocks += Block(classId, BlockKind.CLASS, classTitle(cls), path, rangeOf(cls),
                excluded = members.map { rangeOf(it.function) }.sortedBy { it.start })
            members.forEach { member ->
                val id = "method:${functionKey(member.function)}"
                methodIds[member.function] = id
                blocks += Block(id, BlockKind.METHOD, functionTitle(member.function), path, rangeOf(member.function))
                links += Link(LinkKind.OWNS, classId, id)
            }
        }
        classes.forEach { cls ->
            val classId = classIds.getValue(cls)
            val related = relatedTypes(cls)
            related.forEach { r ->
                if (blocks.none { it.id == r.block.id }) blocks += r.block
                links += Link(r.linkKind, r.block.id, classId)
            }
            memberFunctions(cls).filter { it.owner === cls }.forEach { links += overrides(it.function, methodIds.getValue(it.function), related) }
            injectedTypes(cls).forEach { target ->
                val block = typeBlock(BlockKind.DEPENDENCY, target)
                if (blocks.none { it.id == block.id }) blocks += block
                links += Link(LinkKind.INJECTS, block.id, classId)
            }
        }
        val callables = methodIds.keys.toList()
        callables.forEach { caller ->
            links += calls(caller, methodIds.getValue(caller), methodIds, classIds)
        }
        val classBlockIds = classIds.values.toSet()
        revealDeeper(classes.flatMap(::relatedTypes), revealed, file, classBlockIds, blocks).let { (deeperBlocks, deeperLinks) ->
            blocks += deeperBlocks
            links += deeperLinks
        }
        followCalls(file, callables.filter { methodIds.getValue(it) in followedCalls }.map { methodIds.getValue(it) to it }, followedCalls, added, path)
            .let { (calleeBlocks, calleeLinks) ->
                blocks += calleeBlocks
                links += calleeLinks
            }
        classes.forEach { cls ->
            implementedBy(cls, classIds.getValue(cls), classIds, methodIds, path).let { (implBlocks, implLinks) ->
                implBlocks.forEach { b -> if (blocks.none { it.id == b.id }) blocks += b }
                links += implLinks
            }
        }
        return BuildResult.Supported(BlockModel(blocks, links.distinct()))
    }

    /** `acme.notes.NoteScreen` for `acme/notes/NoteScreen.kt`: the package and the file name without `.kt`. */
    fun moduleFqn(file: KtFile): String {
        val name = file.name.removeSuffix(".kt")
        val pkg = file.packageFqName.asString()
        return if (pkg.isEmpty()) name else "$pkg.$name"
    }

    /**
     * The part of the id after `method:`; every METHOD id, here and in later tasks, comes from this function.
     * A top-level extension whose key equals the key of a member function of a class in the same file
     * (`fun Repo.save()` next to `class Repo { fun save() }`) gets `[ext]` after it.
     */
    fun functionKey(function: KtNamedFunction): String {
        val key = baseKey(function)
        val file = function.parent as? KtFile ?: return key
        val taken = file.declarations.filterIsInstance<KtClassOrObject>()
            .flatMap { cls -> memberFunctions(cls).map { baseKey(it.function) } }
        return if (key in taken) "$key[ext]" else key
    }

    private fun baseKey(function: KtNamedFunction): String {
        val owner = ownerOf(function)
        val siblings = when (val parent = function.parent) {
            is KtFile -> parent.declarations
            else -> owner?.declarations.orEmpty()
        }.filterIsInstance<KtNamedFunction>().filter { it.name != null }
        val key = keys(siblings.map { it to null })[function] ?: function.name.orEmpty()
        val chain = generateSequence(owner) { it.parent?.parent as? KtClassOrObject }.toList().asReversed()
            .map { if (it is KtObjectDeclaration && it.isCompanion()) it.name ?: "Companion" else it.name.orEmpty() }
        return (chain + key).joinToString(".")
    }

    /** `suspend fun load(id: Long): Note`, `@Composable fun Screen(state: UiState)`, `fun String.slug(): String`. */
    fun functionTitle(function: KtNamedFunction): String {
        val annotations = function.annotationEntries.filter { '(' !in it.text }.map(KtAnnotationEntry::getText)
        val parts = mutableListOf<String>()
        parts += annotations
        if (function.hasModifier(KtTokens.SUSPEND_KEYWORD)) parts += "suspend"
        parts += "fun"
        function.typeParameterList?.let { parts += squash(it.text) }
        val receiver = function.receiverTypeReference?.let { squash(it.text) + "." }.orEmpty()
        val params = function.valueParameterList?.let { squash(it.text) } ?: "()"
        val returns = function.typeReference?.let { ": " + squash(it.text) }.orEmpty()
        return parts.joinToString(" ") + " $receiver${function.name}$params$returns"
    }

    /** The keyword a class title starts with: `class`, `data class`, `sealed interface`, `enum class`, `object`, ... */
    fun classKeyword(cls: KtClassOrObject): String {
        if (cls is KtObjectDeclaration) return if (cls.isCompanion()) "companion object" else "object"
        val kt = cls as KtClass
        return when {
            kt.isInterface() -> when {
                kt.isSealed() -> "sealed interface"
                kt.hasModifier(KtTokens.FUN_KEYWORD) -> "fun interface"
                else -> "interface"
            }
            kt.isEnum() -> "enum class"
            kt.isData() -> "data class"
            kt.isSealed() -> "sealed class"
            kt.isAnnotation() -> "annotation class"
            kt.hasModifier(KtTokens.VALUE_KEYWORD) || kt.hasModifier(KtTokens.INLINE_KEYWORD) -> "value class"
            else -> "class"
        }
    }

    private fun classTitle(cls: KtClassOrObject): String {
        val typeParams = (cls as? KtClass)?.typeParameterList?.let { squash(it.text) }.orEmpty()
        val constructor = cls.primaryConstructor?.let { squash(it.text) }.orEmpty()
        val supers = cls.getSuperTypeList()?.let { " : " + squash(it.text) }.orEmpty()
        val gap = if (constructor.isEmpty()) "" else if (constructor.startsWith("(")) "" else " "
        return "${classKeyword(cls)} ${cls.name}$typeParams$gap$constructor$supers"
    }

    /** `package acme.notes · 2 imports`; null when the file has neither a package nor an import. */
    private fun headerOf(file: KtFile, path: String): Block? {
        val directive = file.packageDirective?.takeIf { it.packageNameExpression != null }
        val imports = file.importDirectives
        val parts = listOfNotNull(directive, imports.firstOrNull(), imports.lastOrNull(), directive)
        if (parts.isEmpty()) return null
        val start = parts.minOf { it.textRange.startOffset }
        val end = parts.maxOf { it.textRange.endOffset }
        val pkg = directive?.let { "package ${file.packageFqName.asString()}" }
        val count = if (imports.isEmpty()) null else "${imports.size} ${if (imports.size == 1) "import" else "imports"}"
        return Block(HEADER_ID, BlockKind.HEADER, listOfNotNull(pkg, count).joinToString(" · "), path,
            SourceRange(start, end), collapsed = true)
    }

    private class Member(val function: KtNamedFunction, val owner: KtClassOrObject)

    /** Functions declared directly in [cls] or in its companion object (not in nested classes). */
    private fun memberFunctions(cls: KtClassOrObject): List<Member> {
        val own = cls.declarations.filterIsInstance<KtNamedFunction>().map { Member(it, cls) }
        val companions = cls.declarations.filterIsInstance<KtObjectDeclaration>().filter { it.isCompanion() }
            .flatMap { companion -> companion.declarations.filterIsInstance<KtNamedFunction>().map { Member(it, companion) } }
        return (own + companions).filter { it.function.name != null }
    }

    /** Functions declared in [cls] or its companion, in declaration order. */
    internal fun declaredFunctions(cls: KtClassOrObject): List<KtNamedFunction> = memberFunctions(cls).map { it.function }

    private fun ownerOf(function: KtNamedFunction): KtClassOrObject? = function.parent?.parent as? KtClassOrObject

    /**
     * The id key per function: its name; where a name repeats within one owner, same-named extensions get their
     * receiver in front (`String.slug`) and functions still sharing a key get their parameter types (`save(Int, String)`).
     */
    private fun keys(functions: List<Pair<KtNamedFunction, KtClassOrObject?>>): Map<KtNamedFunction, String> {
        val result = mutableMapOf<KtNamedFunction, String>()
        functions.groupBy { (function, owner) -> owner to function.name }.values.forEach { group ->
            val name = group.first().first.name.orEmpty()
            if (group.size == 1) {
                result[group.first().first] = name
                return@forEach
            }
            val withReceiver = group.map { (function, _) ->
                function to (function.receiverTypeReference?.let { squash(it.text) + "." }.orEmpty() + name)
            }
            val counts = withReceiver.groupingBy { it.second }.eachCount()
            withReceiver.forEach { (function, key) ->
                result[function] = if (counts.getValue(key) == 1) key else "$key(${parameterTypes(function)})"
            }
        }
        return result
    }

    private fun parameterTypes(function: KtNamedFunction): String =
        function.valueParameters.joinToString(", ") { squash(it.typeReference?.text.orEmpty()) }

    private fun squash(text: String): String =
        text.replace(Regex("\\s+"), " ").replace("( ", "(").replace(Regex(",? \\)$"), ")")

    private class Related(val block: Block, val linkKind: LinkKind, val type: PsiElement)

    /** The class a supertype entry or type reference names, or null (unresolved, type alias, type parameter). */
    internal fun resolveType(typeReference: KtTypeReference?): PsiElement? {
        var element = typeReference?.typeElement
        while (element is KtNullableType) element = element.innerType
        val reference = (element as? KtUserType)?.referenceExpression ?: return null
        val target = reference.references.firstNotNullOfOrNull { it.resolve() }
        // `Base()` in a supertype list resolves to Base's constructor when it has an explicit one.
        val type = when (target) {
            is KtConstructor<*> -> target.getContainingClassOrObject()
            is PsiMethod -> target.takeIf { it.isConstructor }?.containingClass
            else -> target
        }
        return type.takeIf { (it is KtClassOrObject || it is PsiClass) && it !is PsiTypeParameter }
    }

    internal fun fqnOf(type: PsiElement): String = when (type) {
        is KtClassOrObject -> type.fqName?.asString() ?: type.name.orEmpty()
        is PsiClass -> JavaBlockBuilder.fqn(type)
        else -> ""
    }

    private fun isInterface(type: PsiElement): Boolean = when (type) {
        is KtClass -> type.isInterface()
        is PsiClass -> type.isInterface
        else -> false
    }

    /** Interfaces, then parents, each with the link kind pointing into the class block. */
    private fun relatedTypes(cls: KtClassOrObject): List<Related> {
        val entries = cls.superTypeListEntries
        val targets = entries.mapNotNull { entry -> resolveType(entry.typeReference)?.let { entry to it } }
            .filter { fqnOf(it.second) != fqnOf(cls) }
        val clsIsInterface = isInterface(cls)
        val related = targets.map { (entry, type) ->
            // A class's supertype is its parent if it is a class and has a constructor call; an interface otherwise.
            val parent = clsIsInterface.not() && !isInterface(type) && entry !is KtDelegatedSuperTypeEntry
            when {
                parent -> Related(typeBlock(BlockKind.PARENT, type), LinkKind.EXTENDS, type)
                clsIsInterface -> Related(typeBlock(BlockKind.INTERFACE, type), LinkKind.EXTENDS, type)
                else -> Related(typeBlock(BlockKind.INTERFACE, type), LinkKind.IMPLEMENTS, type)
            }
        }
        return related.distinctBy { it.block.id }
    }

    /** A collapsed side-lane block for a Kotlin or Java class; sourceless when there is no source to show. */
    private fun typeBlock(kind: BlockKind, type: PsiElement): Block {
        if (type is PsiClass) return JavaBlockBuilder.externalBlock(kind, type)
        type as KtClassOrObject
        val file = type.containingFile
        val virtualFile = file.virtualFile
        val sourceless = file is PsiCompiledElement || virtualFile == null ||
            ProjectFileIndex.getInstance(type.project).isInLibrary(virtualFile)
        return Block(
            id = "${kind.name.lowercase()}:${fqnOf(type)}",
            kind = kind,
            title = "${classKeyword(type)} ${type.name}",
            filePath = virtualFile?.path.orEmpty(),
            range = if (sourceless) SourceRange(0, 0) else rangeOf(type),
            collapsed = true,
            summary = memberFunctions(type).map { functionTitle(it.function) },
            sourceless = sourceless,
        )
    }

    private val INJECT_ANNOTATIONS = setOf(
        "org.springframework.beans.factory.annotation.Autowired",
        "javax.inject.Inject", "jakarta.inject.Inject",
    )
    private val INJECT_DELEGATES = setOf("inject", "viewModel", "viewModels", "activityViewModels")

    /**
     * The project classes [cls] gets injected, in source order, each once: types of primary- and secondary-constructor
     * parameters, then of `@Inject`/`@Autowired` properties and of properties delegated to `by inject()`,
     * `by viewModel()`, `by viewModels()` or `by activityViewModels()` (the type argument if written, else the
     * declared type). Each is a [KtClassOrObject] or a Java [PsiClass] from the project's own code.
     */
    fun injectedTypes(cls: KtClassOrObject): List<PsiElement> {
        val references = mutableListOf<KtTypeReference?>()
        cls.primaryConstructor?.valueParameters?.forEach { references += it.typeReference }
        cls.secondaryConstructors.forEach { constructor -> constructor.valueParameters.forEach { references += it.typeReference } }
        cls.declarations.filterIsInstance<KtProperty>().forEach { property ->
            if (property.annotationEntries.any { annotated(it) }) {
                references += property.typeReference
            } else {
                val call = property.delegateExpression as? KtCallExpression
                if (call != null && call.calleeExpression?.text in INJECT_DELEGATES) {
                    references += call.typeArguments.firstOrNull()?.typeReference ?: property.typeReference
                }
            }
        }
        val own = fqnOf(cls)
        return references.mapNotNull(::resolveType)
            .filter { fqnOf(it) != own && inProject(it) }
            .distinctBy(::fqnOf)
    }

    /** By qualified name when the annotation resolves, else by its short name. */
    private fun annotated(entry: KtAnnotationEntry): Boolean {
        val reference = (entry.typeReference?.typeElement as? KtUserType)?.referenceExpression ?: return false
        val target = reference.references.firstNotNullOfOrNull { it.resolve() }
        if (target != null) return fqnOf(target) in INJECT_ANNOTATIONS
        return INJECT_ANNOTATIONS.any { it.substringAfterLast('.') == reference.getReferencedName() }
    }

    /** Project code only: libraries are never pulled onto the canvas. */
    private fun inProject(element: PsiElement): Boolean {
        val files = ProjectFileIndex.getInstance(element.project)
        return element.containingFile?.virtualFile?.let { files.isInContent(it) && !files.isInLibrary(it) } == true
    }

    /**
     * An `override` function links to each related type that declares the function it overrides. The overridden
     * function is found by name and parameter count on the type's own PSI (no analysis, same in K1 and K2); among
     * several, the one whose parameter types read the same wins.
     */
    private fun overrides(function: KtNamedFunction, id: String, related: List<Related>): List<Link> {
        if (!function.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return emptyList()
        val name = function.name ?: return emptyList()
        val types = function.valueParameters.map { squash(it.typeReference?.text.orEmpty()) }
        return related.mapNotNull { r ->
            val declared = declaredMatching(r.type, name, types.size)
            val overridden = declared.firstOrNull { parameterTypesOf(it) == types } ?: declared.firstOrNull() ?: return@mapNotNull null
            val landing = when (overridden) {
                is PsiMethod -> (overridden.navigationElement as? PsiMethod ?: overridden).takeUnless { r.block.sourceless }
                else -> overridden
            }
            Link(LinkKind.OVERRIDES, id, r.block.id, landing?.let(::rangeOf))
        }
    }

    /** Functions of [type]'s own PSI (Kotlin or Java) with this name and parameter count. */
    private fun declaredMatching(type: PsiElement, name: String, count: Int): List<PsiElement> = when (type) {
        is KtClassOrObject -> type.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == name && it.valueParameters.size == count }
        is PsiClass -> type.findMethodsByName(name, false).filter { it.parameterList.parametersCount == count }
        else -> emptyList()
    }

    private fun parameterTypesOf(function: PsiElement): List<String> = when (function) {
        is KtNamedFunction -> function.valueParameters.map { squash(it.typeReference?.text.orEmpty()) }
        is PsiMethod -> function.parameterList.parameters.map { it.type.presentableText }
        else -> emptyList()
    }

    /**
     * CALLS from [caller] to each function of this file (or constructor call to each class of this file) that a call
     * or callable reference inside it resolves to, lambdas included. Library, Java and unresolved targets are skipped.
     */
    private fun calls(caller: KtNamedFunction, callerId: String, methodIds: Map<KtNamedFunction, String>, classIds: Map<KtClassOrObject, String>): List<Link> {
        val references = PsiTreeUtil.findChildrenOfType(caller, KtCallExpression::class.java).mapNotNull { it.calleeExpression } +
            PsiTreeUtil.findChildrenOfType(caller, KtCallableReferenceExpression::class.java).map { it.callableReference }
        return references.mapNotNull { expression ->
            when (val target = expression.references.firstNotNullOfOrNull { it.resolve() }) {
                is KtNamedFunction -> methodIds[target]
                is KtConstructor<*> -> classIds[target.getContainingClassOrObject()]
                is KtClassOrObject -> classIds[target]
                else -> null
            }
        }.filter { it != callerId }.distinct().map { Link(LinkKind.CALLS, callerId, it) }
    }

    private fun isAbstract(function: PsiElement): Boolean = when (function) {
        is KtNamedFunction -> function.hasModifier(KtTokens.ABSTRACT_KEYWORD)
        is PsiMethod -> function.hasModifierProperty(PsiModifier.ABSTRACT)
        else -> false
    }

    private fun outermostClass(function: KtNamedFunction): KtClassOrObject? =
        generateSequence(ownerOf(function)) { it.parent?.parent as? KtClassOrObject }.lastOrNull()

    /**
     * `acme.Repo::save`, `acme.Repo::Companion.create`, `acme.Audit::audit` (top-level functions are owned by their
     * module fqn): the part of a callee id after `callee:`. Null for local functions and members of anonymous objects.
     */
    fun calleeKey(function: KtNamedFunction): String? {
        val key = functionKey(function)
        val outer = outermostClass(function)
        if (outer == null) {
            val file = function.parent as? KtFile ?: return null
            return "${moduleFqn(file)}::$key"
        }
        val fqn = outer.fqName?.asString() ?: return null
        return "$fqn::${key.removePrefix(outer.name + ".")}"
    }

    /** The named functions of [file] that get a METHOD block: top-level ones, then members of top-level classes and their companions. */
    fun canvasFunctions(file: KtFile): List<KtNamedFunction> =
        file.declarations.filterIsInstance<KtNamedFunction>().filter { it.name != null } +
            file.declarations.filterIsInstance<KtClassOrObject>().flatMap { cls -> memberFunctions(cls).map { it.function } }

    private fun ownerName(function: KtNamedFunction): String =
        outermostClass(function)?.name ?: (function.parent as? KtFile)?.let(::moduleFqn).orEmpty()

    private fun kotlinCalleeBlock(id: String, function: KtNamedFunction): Block =
        Block(id, BlockKind.CALLEE, "${ownerName(function)}::${functionTitle(function)}", function.containingFile.virtualFile.path, rangeOf(function))

    /**
     * A [kind] block for a Kotlin class or function seen from a Java canvas: a class gets the side-lane type block
     * (`parent:acme.Base`), a function the callee block under its Kotlin callee key (`callee:acme.NoteScreen::format`,
     * also when Java calls it through the `NoteScreenKt` facade). Null for anything else.
     */
    internal fun foreignBlock(element: PsiElement, kind: BlockKind): Block? = when (element) {
        is KtClassOrObject -> element.fqName?.let { typeBlock(kind, element) }
        is KtNamedFunction -> calleeKey(element)?.let { key ->
            kotlinCalleeBlock("${kind.name.lowercase()}:$key", element).copy(kind = kind)
        }
        else -> null
    }

    /** The id and block of a callee (Kotlin function or Java method) in project code, or null. */
    private fun calleeFor(element: PsiElement): Pair<String, Block>? {
        val target = kotlinSource(element)
        if (!inProject(target)) return null
        return when (target) {
            is KtNamedFunction -> calleeKey(target)?.let { "callee:$it" }?.let { it to kotlinCalleeBlock(it, target) }
            is PsiMethod -> {
                if (target.isConstructor || target is LightElement || target.containingClass == null) return null
                val id = "callee:${JavaBlockBuilder.fqn(target.containingClass!!)}::${JavaBlockBuilder.methodKey(target)}"
                id to JavaBlockBuilder.calleeBlock(id, target)
            }
            else -> null
        }
    }

    /** Functions and methods [element] calls or refers to (lambdas included); constructors and unresolved calls left out. */
    private fun calledTargets(element: PsiElement): List<PsiElement> = when (element) {
        is KtNamedFunction -> {
            val references = PsiTreeUtil.findChildrenOfType(element, KtCallExpression::class.java).mapNotNull { it.calleeExpression } +
                PsiTreeUtil.findChildrenOfType(element, KtCallableReferenceExpression::class.java).map { it.callableReference }
            references.mapNotNull { e -> e.references.firstNotNullOfOrNull { it.resolve() } }.filter { it is KtNamedFunction || it is PsiMethod }
        }
        is PsiMethod -> JavaBlockBuilder.calledMethods(element).map(::kotlinSource)
        else -> emptyList()
    }

    /**
     * A Java call into Kotlin resolves to a light method (a LightElement); its Kotlin function is what gets the block
     * and what following goes on from. Anything else stays as it is.
     */
    private fun kotlinSource(element: PsiElement): PsiElement =
        (element as? PsiMethod)?.navigationElement as? KtNamedFunction ?: element

    /** "Go to Implementation" over project code (DefinitionsScopedSearch, as Java does). Needs a background read action: K2 refuses it on the EDT. */
    private fun definitions(element: PsiElement): List<PsiElement> =
        DefinitionsScopedSearch.search(element, GlobalSearchScope.projectScope(element.project)).findAll().toList()

    /** Project implementations of an interface/abstract [function] (at most [MAX_IMPLEMENTATIONS]). */

    private fun implementationsOf(function: PsiElement): List<Pair<String, PsiElement>> =
        definitions(function).asSequence()
            .filter { it != function && (it is KtNamedFunction || it is PsiMethod) && !isAbstract(it) }
            .filterNot { (it as? KtNamedFunction)?.let(::inAnonymousObject) == true || (it as? PsiMethod)?.containingClass is PsiAnonymousClass }
            .mapNotNull { impl -> calleeFor(impl)?.let { it.first to impl } }
            .sortedBy { it.first }
            .distinctBy { it.first }
            .take(MAX_IMPLEMENTATIONS)
            .toList()

    private fun inAnonymousObject(function: KtNamedFunction): Boolean = (ownerOf(function) as? KtObjectDeclaration)?.isObjectLiteral() == true

    private fun isInterfaceMember(function: PsiElement): Boolean = when (function) {
        is KtNamedFunction -> (ownerOf(function) as? KtClass)?.isInterface() == true
        is PsiMethod -> function.containingClass?.isInterface == true
        else -> false
    }

    /** Parents of revealed blocks, as deep as revealed (as Java's revealDeeper); Java supertypes are followed too. */
    private fun revealDeeper(related: List<Related>, revealed: Set<String>, file: KtFile, ownClassBlocks: Set<String>, shown: List<Block>): Pair<List<Block>, List<Link>> {
        val known = related.associateBy { it.block.id }.toMutableMap()
        val queue = ArrayDeque(revealed.filter { it in known })
        val seen = mutableSetOf<String>()
        val ownFqns = file.declarations.filterIsInstance<KtClassOrObject>().mapNotNull { it.fqName?.asString() }.toSet()
        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            for (deeper in parentsOf(known.getValue(id).type)) {
                val deeperId = deeper.block.id
                if (fqnOf(deeper.type) in ownFqns) continue
                if (deeperId !in known) {
                    known[deeperId] = deeper
                    if (shown.none { it.id == deeperId }) blocks += deeper.block
                }
                links += Link(deeper.linkKind, deeperId, id)
                if (deeperId in revealed) queue += deeperId
            }
        }
        return blocks to links.distinct()
    }

    private fun parentsOf(type: PsiElement): List<Related> = when (type) {
        is KtClassOrObject -> relatedTypes(type)
        is PsiClass -> {
            val parentKind = if (type.isInterface) BlockKind.INTERFACE else BlockKind.PARENT
            val implemented = type.implementsList?.referenceElements.orEmpty().mapNotNull { it.resolve() as? PsiClass }
                .map { Related(JavaBlockBuilder.externalBlock(BlockKind.INTERFACE, it), LinkKind.IMPLEMENTS, it) }
            val extended = type.extendsList?.referenceElements.orEmpty().mapNotNull { it.resolve() as? PsiClass }
                .map { Related(JavaBlockBuilder.externalBlock(parentKind, it), LinkKind.EXTENDS, it) }
            (implemented + extended).distinctBy { it.block.id }
        }
        else -> emptyList()
    }

    /**
     * For an interface, abstract class or sealed class: the project classes that implement / extend it (at most
     * [MAX_IMPLEMENTERS], alphabetical, plus a "more" block), and per class the functions that implement or override
     * one of this type's. Implementers in the same file are linked class to class.
     */
    private fun implementedBy(cls: KtClassOrObject, classId: String, classIds: Map<KtClassOrObject, String>, methodIds: Map<KtNamedFunction, String>, path: String): Pair<List<Block>, List<Link>> {
        val kt = cls as? KtClass ?: return emptyList<Block>() to emptyList()
        if (!kt.isInterface() && !kt.hasModifier(KtTokens.ABSTRACT_KEYWORD) && !kt.isSealed()) return emptyList<Block>() to emptyList()
        val own = fqnOf(cls)
        val candidates = definitions(cls)
            .filter { (it is KtClassOrObject && !(it is KtObjectDeclaration && it.isObjectLiteral())) || (it is PsiClass && it !is PsiAnonymousClass && it !is PsiTypeParameter) }
            .filter { fqnOf(it) != own && !isInterface(it) && inProject(it) && fqnOf(it).isNotEmpty() }
            .distinctBy(::fqnOf)
            .sortedWith(compareBy({ (it as? KtClassOrObject)?.name ?: (it as PsiClass).name }, { fqnOf(it) }))
        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        val methods = memberFunctions(cls).filter { it.owner === cls }.map { it.function }
        val external = mutableListOf<PsiElement>()
        for (implementer in candidates) {
            val sameFile = classIds.entries.firstOrNull { it.key === implementer }
            if (sameFile != null) {
                links += Link(LinkKind.IMPLEMENTED_BY, classId, sameFile.value)
            } else if (implementer.containingFile != cls.containingFile) {
                external += implementer
            }
        }
        for (implementer in external.take(MAX_IMPLEMENTERS)) {
            val implementations = methods.mapNotNull { method -> implementationOf(implementer, method)?.let { method to it } }
            val block = typeBlock(BlockKind.IMPLEMENTER, implementer).copy(summary = implementations.map { titleOf(it.second) }.distinct())
            blocks += block
            links += Link(LinkKind.IMPLEMENTED_BY, classId, block.id)
            for ((method, implementation) in implementations) {
                val key = when (implementation) {
                    is KtNamedFunction -> calleeKey(implementation)
                    is PsiMethod -> "${JavaBlockBuilder.fqn(implementation.containingClass!!)}::${JavaBlockBuilder.methodKey(implementation)}"
                    else -> null
                } ?: continue
                val id = "implementation:$key"
                val range = when (implementation) {
                    is PsiMethod -> JavaBlockBuilder.rangeOf(implementation.navigationElement as? PsiMethod ?: implementation)
                    else -> rangeOf(implementation)
                }
                if (blocks.none { it.id == id }) blocks += Block(id, BlockKind.IMPLEMENTATION, "${(implementer as? KtClassOrObject)?.name ?: (implementer as PsiClass).name}::${titleOf(implementation)}",
                    implementation.containingFile.virtualFile.path, range, collapsed = true)
                links += Link(LinkKind.OWNS, block.id, id)
                links += Link(LinkKind.OVERRIDES, id, methodIds.getValue(method), rangeOf(method))
            }
        }
        val rest = external.drop(MAX_IMPLEMENTERS)
        if (rest.isNotEmpty()) {
            blocks += Block(MORE_ID, BlockKind.MORE, "and ${rest.size} more", path, SourceRange(0, 0), collapsed = true,
                summary = rest.map { (it as? KtClassOrObject)?.name ?: (it as PsiClass).name.orEmpty() })
            links += Link(LinkKind.IMPLEMENTED_BY, classId, MORE_ID)
        }
        return blocks to links.distinct()
    }

    private fun titleOf(function: PsiElement): String = when (function) {
        is KtNamedFunction -> functionTitle(function)
        is PsiMethod -> JavaBlockBuilder.methodTitle(function)
        else -> ""
    }

    /** The function of [implementer] that implements [method]: same name and parameter count, identical parameter types preferred. */
    private fun implementationOf(implementer: PsiElement, method: KtNamedFunction): PsiElement? {
        val name = method.name ?: return null
        val types = method.valueParameters.map { squash(it.typeReference?.text.orEmpty()) }
        val declared = declaredMatching(implementer, name, types.size)
        return declared.firstOrNull { parameterTypesOf(it) == types } ?: declared.firstOrNull()
    }

    /** "+ calls": callees in other project files become editable blocks, as in Java and Python. */
    private fun followCalls(file: KtFile, roots: List<Pair<String, KtNamedFunction>>, followed: Set<String>, added: Set<String>, path: String): Pair<List<Block>, List<Link>> {
        if (roots.isEmpty() && added.isEmpty()) return emptyList<Block>() to emptyList()
        val queue = ArrayDeque<Pair<String, PsiElement>>(roots)
        val seen = mutableSetOf<String>()
        val blocks = linkedMapOf<String, Block>()
        val links = mutableListOf<Link>()
        var truncated = false
        val firstRoot = roots.firstOrNull()?.first
        // Add method… and AI flows: each added key is a CALLEE block of its own, as a followed call would make it.
        for (key in added) {
            if (key.endsWith("::class")) {
                val target = classByFqn(file.project, key.removeSuffix("::class")) ?: continue
                if (target.containingFile == file || !inProject(target)) continue
                val id = "callee:$key"
                blocks.getOrPut(id) { typeBlock(BlockKind.CALLEE, target).let { it.copy(id = id, collapsed = it.sourceless) } }
                continue
            }
            val function = functionByKey(file.project, key) ?: continue
            // A function of the open file already has a block of its own (Add method… just focuses it).
            if (function.containingFile == file) continue
            val (id, block) = calleeFor(function) ?: continue
            blocks.getOrPut(id) { block }
            if (id in followed) queue += id to function
        }
        while (queue.isNotEmpty()) {
            val (id, caller) = queue.removeFirst()
            if (!seen.add(id)) continue
            if (blocks.size >= MAX_CHAIN_BLOCKS) {
                truncated = true
                break
            }
            val allCallees = calledTargets(caller).filter { it.containingFile != file }.mapNotNull { target -> calleeFor(target)?.let { it to target } }
                .distinctBy { it.first.first }
            val callees = allCallees.take(MAX_CALLEES)
            if (allCallees.size > MAX_CALLEES) {
                val moreId = "more:calls:$id"
                blocks[moreId] = Block(moreId, BlockKind.MORE, "and ${allCallees.size - MAX_CALLEES} more calls", path, SourceRange(0, 0), collapsed = true)
                links += Link(LinkKind.CALLS_INTO, id, moreId)
            }
            for ((callee, target) in callees) {
                if (blocks.size >= MAX_CHAIN_BLOCKS) {
                    truncated = true
                    break
                }
                val (calleeId, block) = callee
                blocks.getOrPut(calleeId) { block }
                links += Link(LinkKind.CALLS_INTO, id, calleeId)
                if (calleeId in followed) queue += calleeId to target
                if (isInterfaceMember(target) || isAbstract(target)) {
                    for ((implId, implementation) in implementationsOf(target)) {
                        val implBlock = calleeFor(implementation) ?: continue
                        blocks.getOrPut(implId) { implBlock.second }
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
        return blocks.values.toList() to links.distinct()
    }

    /** The Kotlin or Java class with this fqn, from the project's own sources. */
    private fun classByFqn(project: Project, fqn: String): PsiElement? {
        val cls = JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.projectScope(project)) ?: return null
        return (cls.navigationElement as? KtClassOrObject) ?: cls
    }

    /** `Owner::key` (owner = class fqn or module fqn, key without the class prefix) to that function or method, or null. */
    private fun functionByKey(project: Project, key: String): PsiElement? {
        val owner = key.substringBefore("::")
        val short = key.substringAfter("::")
        when (val cls = classByFqn(project, owner)) {
            is KtClassOrObject -> memberFunctions(cls).map { it.function }.firstOrNull { calleeKey(it) == key }?.let { return it }
            is PsiClass -> JavaBlockBuilder.findOwnMethod(cls, short)?.let { return it.navigationElement as? PsiMethod ?: it }
        }
        val module = owner.substringAfterLast('.')
        return FilenameIndex.getVirtualFilesByName("$module.kt", GlobalSearchScope.projectScope(project))
            .mapNotNull { PsiManager.getInstance(project).findFile(it) as? KtFile }
            .filter { moduleFqn(it) == owner }
            .flatMap { it.declarations.filterIsInstance<KtNamedFunction>() }
            .firstOrNull { calleeKey(it) == key }
    }

    /**
     * An added key resolved the way this builder's own `added` handling does, Kotlin declarations only: `Fqn::class` to
     * a Kotlin class, `Owner::key` to a Kotlin function (members and top-level). For Java canvases.
     */
    internal fun kotlinElementForKey(project: Project, key: String): PsiElement? =
        if (key.endsWith("::class")) classByFqn(project, key.removeSuffix("::class")) as? KtClassOrObject
        else functionByKey(project, key) as? KtNamedFunction

    /** Kotlin PSI ranges of a declaration include its KDoc and annotations. */
    private fun rangeOf(element: PsiElement): SourceRange = SourceRange(element.textRange.startOffset, element.textRange.endOffset)
}
