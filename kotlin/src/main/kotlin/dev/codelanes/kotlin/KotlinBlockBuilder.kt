package dev.codelanes.kotlin

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiClass
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
    private fun resolveType(typeReference: KtTypeReference?): PsiElement? {
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

    private fun fqnOf(type: PsiElement): String = when (type) {
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
            val declared: List<PsiElement> = when (val type = r.type) {
                is KtClassOrObject -> type.declarations.filterIsInstance<KtNamedFunction>().filter { it.name == name && it.valueParameters.size == types.size }
                is PsiClass -> type.findMethodsByName(name, false).filter { it.parameterList.parametersCount == types.size }
                else -> emptyList()
            }
            val overridden = declared.firstOrNull { parameterTypesOf(it) == types } ?: declared.firstOrNull() ?: return@mapNotNull null
            val landing = when (overridden) {
                is PsiMethod -> (overridden.navigationElement as? PsiMethod ?: overridden).takeUnless { r.block.sourceless }
                else -> overridden
            }
            Link(LinkKind.OVERRIDES, id, r.block.id, landing?.let(::rangeOf))
        }
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

    /** Kotlin PSI ranges of a declaration include its KDoc and annotations. */
    private fun rangeOf(element: PsiElement): SourceRange = SourceRange(element.textRange.startOffset, element.textRange.endOffset)
}
