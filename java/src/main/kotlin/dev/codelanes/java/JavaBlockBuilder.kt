package dev.codelanes.java

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReferenceList
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiType
import com.intellij.psi.SyntheticElement
import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.util.MethodSignatureUtil
import com.intellij.psi.util.PsiTreeUtil
import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
import dev.codelanes.model.SourceRange

/** Turns a Java file with one top-level type into a [BlockModel]; anything else is [BuildResult.Unsupported]. */
object JavaBlockBuilder {
    const val HEADER_ID = "header"
    const val MORE_ID = "more:implementers"
    const val MAX_IMPLEMENTERS = 10
    const val MAX_CALLEES = 10
    const val MAX_CHAIN_BLOCKS = 80
    const val MORE_CHAIN_ID = "more:chain"
    const val MAX_IMPLEMENTATIONS = 5

    fun build(file: PsiFile, revealed: Set<String> = emptySet(), followedCalls: Set<String> = emptySet(), added: Set<String> = emptySet()): BuildResult {
        unsupportedReason(file)?.let { return BuildResult.Unsupported(it) }
        val cls = (file as PsiJavaFile).classes.single()
        val classRange = rangeOf(cls)
        val path = file.virtualFile.path
        val classId = "class:${fqn(cls)}"
        val methods = ownMethods(cls)
        // Pasted twice, or overloads with the same erasure: keep the last good canvas until it compiles again.
        methods.groupBy(::methodId).values.firstOrNull { it.size > 1 }?.let { return BuildResult.Unsupported("Duplicate method ${it.first().name}") }
        val methodBlocks = methods.map { Block(methodId(it), BlockKind.METHOD, methodTitle(it), path, rangeOf(it)) }

        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        blocks += Block(HEADER_ID, BlockKind.HEADER, headerTitle(file), path, SourceRange(0, classRange.start), collapsed = true)
        blocks += Block(classId, BlockKind.CLASS, classTitle(cls), path, classRange, excluded = methodBlocks.map { it.range })
        blocks += methodBlocks
        links += methodBlocks.map { Link(LinkKind.OWNS, classId, it.id) }
        val related = relatedTypes(cls)
        related.forEach { (block, linkKind, _) ->
            blocks += block
            links += Link(linkKind, block.id, classId)
        }
        links += overrides(methods, related)
        followCalls(cls, methods, followedCalls, added, path).let { (calleeBlocks, calleeLinks) ->
            blocks += calleeBlocks
            links += calleeLinks
        }
        revealDeeper(related, revealed, classId).let { (deeperBlocks, deeperLinks) ->
            blocks += deeperBlocks
            links += deeperLinks
        }
        dependencies(cls).forEach { block ->
            if (blocks.none { it.id == block.id }) blocks += block
            links += Link(LinkKind.INJECTS, block.id, classId)
        }
        implementedBy(cls, classId, methods, path).let { (implBlocks, implLinks) ->
            blocks += implBlocks
            links += implLinks
        }
        links += calls(methods)
        return BuildResult.Supported(BlockModel(blocks, links))
    }

    private val INJECT_ANNOTATIONS = setOf(
        "org.springframework.beans.factory.annotation.Autowired",
        "javax.inject.Inject", "jakarta.inject.Inject",
        "javax.annotation.Resource", "jakarta.annotation.Resource",
    )

    internal fun externalBlock(kind: BlockKind, target: PsiClass): Block {
        val sourceless = isSourceless(target)
        val source = if (sourceless) target else (target.navigationElement as? PsiClass ?: target)
        return Block(
            id = "${kind.name.lowercase()}:${fqn(target)}",
            kind = kind,
            title = "${keyword(target)} ${target.name}",
            filePath = source.containingFile?.virtualFile?.path.orEmpty(),
            range = if (sourceless) SourceRange(0, 0) else rangeOf(source),
            collapsed = true,
            summary = ownMethods(source).map(::methodTitle),
            sourceless = sourceless,
        )
    }

    private fun isAbstract(method: PsiMethod) = method.hasModifierProperty(PsiModifier.ABSTRACT)

    private fun calleeBlock(id: String, method: PsiMethod): Block {
        val owner = method.containingClass!!
        if (isSourceless(owner)) {
            return Block(id, BlockKind.CALLEE, "${owner.name}::${methodTitle(method)}", owner.containingFile?.virtualFile?.path.orEmpty(),
                SourceRange(0, 0), collapsed = true, sourceless = true)
        }
        val source = method.navigationElement as? PsiMethod ?: method
        return Block(id, BlockKind.CALLEE, "${owner.name}::${methodTitle(source)}", source.containingFile.virtualFile.path, rangeOf(source))
    }

    /** "Fqn::key" → that method of that class, or null if either no longer exists. */
    private fun methodByKey(context: PsiElement, key: String): PsiMethod? {
        val cls = classByFqn(context, key.substringBefore("::")) ?: return null
        return findOwnMethod(cls, key.substringAfter("::"))
    }

    /** Project implementations of an interface/abstract [method] (at most [MAX_IMPLEMENTATIONS]). */
    private fun implementationsOf(method: PsiMethod): List<Pair<String, PsiMethod>> =
        OverridingMethodsSearch.search(method, GlobalSearchScope.projectScope(method.project), true).findAll().asSequence()
            .filter { !isAbstract(it) && !isGenerated(it) && inProject(it) && it.containingClass !is PsiAnonymousClass }
            .mapNotNull { impl -> impl.containingClass?.let { "callee:${fqn(it)}::${methodKey(impl)}" to impl } }
            .sortedBy { it.first }
            .distinctBy { it.first }
            .take(MAX_IMPLEMENTATIONS)
            .toList()

    /** "+ calls", as in PhpBlockBuilder.followCalls: callees in other project classes become editable blocks. */
    private fun followCalls(cls: PsiClass, methods: List<PsiMethod>, followed: Set<String>, added: Set<String>, path: String): Pair<List<Block>, List<Link>> {
        if (followed.isEmpty() && added.isEmpty()) return emptyList<Block>() to emptyList()
        val queue = ArrayDeque(methods.map { methodId(it) to it }.filter { it.first in followed })
        val seen = mutableSetOf<String>()
        val blocks = linkedMapOf<String, Block>()
        var truncated = false
        val firstRoot = queue.firstOrNull()?.first
        for (key in added) {
            if (key.endsWith("::class")) {
                val target = classByFqn(cls, key.removeSuffix("::class")) ?: continue
                val id = "callee:$key"
                blocks.getOrPut(id) { externalBlock(BlockKind.CALLEE, target).let { if (it.sourceless) it.copy(id = id) else it.copy(id = id, collapsed = false) } }
                continue
            }
            val method = methodByKey(cls, key) ?: continue
            val id = "callee:${fqn(method.containingClass!!)}::${methodKey(method)}"
            blocks.getOrPut(id) { calleeBlock(id, method) }
            if (id in followed) queue += id to method
        }
        val links = mutableListOf<Link>()
        while (queue.isNotEmpty()) {
            val (id, caller) = queue.removeFirst()
            if (!seen.add(id)) continue
            if (blocks.size >= MAX_CHAIN_BLOCKS) {
                truncated = true
                break
            }
            val allCallees = calledMethods(caller)
                .filter { !isGenerated(it) && it.containingClass.let { c -> c != null && fqn(c) != fqn(cls) } && inProject(it) }
                .distinct()
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
                val owner = callee.containingClass!!
                val calleeId = "callee:${fqn(owner)}::${methodKey(callee)}"
                blocks.getOrPut(calleeId) { calleeBlock(calleeId, callee) }
                links += Link(LinkKind.CALLS_INTO, id, calleeId)
                if (calleeId in followed) queue += calleeId to callee
                // A call into an interface/abstract method: the real work happens in its implementations.
                if (owner.isInterface || isAbstract(callee)) {
                    for ((implId, implementation) in implementationsOf(callee)) {
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
        return blocks.values.toList() to links.distinct()
    }

    /**
     * For an interface or abstract class: the project classes that implement / extend it (at most [MAX_IMPLEMENTERS],
     * alphabetical, plus a "more" block), and per class only the methods that implement or override one of this type's.
     */
    private fun implementedBy(cls: PsiClass, classId: String, methods: List<PsiMethod>, path: String): Pair<List<Block>, List<Link>> {
        if (!cls.isInterface && !cls.hasModifierProperty(PsiModifier.ABSTRACT)) return emptyList<Block>() to emptyList()
        val candidates = ClassInheritorsSearch.search(cls, GlobalSearchScope.projectScope(cls.project), false).findAll()
            .filter { !it.isInterface && it !is PsiAnonymousClass && fqn(it) != fqn(cls) && inProject(it) }
            .distinctBy(::fqn)
            .sortedWith(compareBy({ it.name }, { fqn(it) }))
        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        for (implementer in candidates.take(MAX_IMPLEMENTERS)) {
            // findMethodBySuperMethod substitutes type parameters: `save(T)` of Repo<Order> is `save(Order)`.
            val implemented = methods.mapNotNull { MethodSignatureUtil.findMethodBySuperMethod(implementer, it, false) }
            val block = externalBlock(BlockKind.IMPLEMENTER, implementer).copy(summary = implemented.distinct().map(::methodTitle))
            blocks += block
            links += Link(LinkKind.IMPLEMENTED_BY, classId, block.id)
            for (method in methods) {
                val implementation = MethodSignatureUtil.findMethodBySuperMethod(implementer, method, false) ?: continue
                val id = "implementation:${fqn(implementer)}::${methodKey(implementation)}"
                // One method can implement two of this type's (`put(T)` and `put(Order)` of Store<Order>).
                if (blocks.none { it.id == id }) blocks += Block(id, BlockKind.IMPLEMENTATION, "${implementer.name}::${methodTitle(implementation)}",
                    implementer.containingFile.virtualFile.path, rangeOf(implementation), collapsed = true)
                links += Link(LinkKind.OWNS, block.id, id)
                links += Link(LinkKind.OVERRIDES, id, methodId(method), rangeOf(method))
            }
        }
        val rest = candidates.drop(MAX_IMPLEMENTERS)
        if (rest.isNotEmpty()) {
            blocks += Block(MORE_ID, BlockKind.MORE, "and ${rest.size} more", path, SourceRange(0, 0), collapsed = true, summary = rest.mapNotNull { it.name })
            links += Link(LinkKind.IMPLEMENTED_BY, classId, MORE_ID)
        }
        return blocks to links
    }

    private data class Related(val block: Block, val linkKind: LinkKind, val type: PsiClass)

    private fun resolved(list: PsiReferenceList?): List<PsiClass> =
        list?.referenceElements.orEmpty().mapNotNull { it.resolve() as? PsiClass }.distinctBy(::fqn)

    /** Interfaces, then parents, each with the link kind pointing into the class block. */
    private fun relatedTypes(cls: PsiClass): List<Related> {
        val parentKind = if (cls.isInterface) BlockKind.INTERFACE else BlockKind.PARENT
        val related = resolved(cls.implementsList).map { Related(externalBlock(BlockKind.INTERFACE, it), LinkKind.IMPLEMENTS, it) } +
            resolved(cls.extendsList).map { Related(externalBlock(parentKind, it), LinkKind.EXTENDS, it) }
        return related.distinctBy { it.block.id } // half-typed `interface Foo extends J implements J`
    }

    /** A method links to each related type that declares a method it overrides. */
    private fun overrides(methods: List<PsiMethod>, related: List<Related>): List<Link> =
        methods.flatMap { method ->
            val supers = method.findSuperMethods().toSet()
            related.mapNotNull { r ->
                r.type.methods.firstOrNull { it in supers }?.let { overridden ->
                    val landing = (overridden.navigationElement as? PsiMethod)?.takeUnless { r.block.sourceless }
                    Link(LinkKind.OVERRIDES, methodId(method), r.block.id, landing?.let(::rangeOf))
                }
            }
        }

    /** Same as PhpBlockBuilder.revealDeeper: parents of revealed blocks, as deep as revealed. */
    private fun revealDeeper(related: List<Related>, revealed: Set<String>, classId: String): Pair<List<Block>, List<Link>> {
        val known = related.associateBy { it.block.id }.toMutableMap()
        val queue = ArrayDeque(revealed.filter { it in known })
        val seen = mutableSetOf<String>()
        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            for (deeper in relatedTypes(known.getValue(id).type)) {
                val deeperId = deeper.block.id
                if (deeperId == classId) continue
                if (deeperId !in known) {
                    known[deeperId] = deeper
                    blocks += deeper.block
                }
                links += Link(deeper.linkKind, deeperId, id)
                if (deeperId in revealed) queue += deeperId
            }
        }
        return blocks to links.distinct()
    }

    /**
     * Injected types: constructor parameters, @Autowired/@Inject/@Resource fields, and the fields Lombok's
     * @RequiredArgsConstructor (final, no initializer) or @AllArgsConstructor (all) turn into constructor parameters.
     */
    private fun dependencies(cls: PsiClass): List<Block> {
        val fields = cls.fields.filter { it !is PsiEnumConstant && !it.hasModifierProperty(PsiModifier.STATIC) }
        val types = mutableListOf<PsiType>()
        cls.constructors.forEach { constructor -> constructor.parameterList.parameters.forEach { types += it.type } }
        fields.filter { field -> field.annotations.any { annotated(it, INJECT_ANNOTATIONS) } }.forEach { types += it.type }
        if (cls.annotations.any { annotated(it, setOf("lombok.RequiredArgsConstructor")) }) {
            fields.filter { it.hasModifierProperty(PsiModifier.FINAL) && it.initializer == null }.forEach { types += it.type }
        }
        if (cls.annotations.any { annotated(it, setOf("lombok.AllArgsConstructor")) }) fields.forEach { types += it.type }
        return types.mapNotNull { (it as? PsiClassType)?.resolve() }
            .filter { it !is PsiTypeParameter && fqn(it) != fqn(cls) && !isJdk(it) }
            .distinctBy(::fqn)
            .map { externalBlock(BlockKind.DEPENDENCY, it) }
    }

    /** By qualified name when the annotation resolves, else by its short name. */
    private fun annotated(annotation: PsiAnnotation, names: Set<String>): Boolean {
        if (annotation.resolveAnnotationType() != null) return annotation.qualifiedName in names
        val short = annotation.nameReferenceElement?.referenceName ?: return false
        return names.any { it.substringAfterLast('.') == short }
    }

    private fun isJdk(cls: PsiClass): Boolean {
        val name = fqn(cls)
        return name.startsWith("java.") || (name.startsWith("javax.") && !name.startsWith("javax.inject."))
    }

    /** Why [file] can't be shown as blocks, or null. Structure only, no resolving: cheap while indexing. */
    fun unsupportedReason(file: PsiFile): String? {
        if (file !is PsiJavaFile) return "Not a Java file"
        if (file is PsiCompiledElement) return "Compiled class without source"
        // JDK and library sources (Cmd+B into them) stay in the text editor. The file index works while indexing.
        if (file.virtualFile?.let { ProjectFileIndex.getInstance(file.project).isInLibrary(it) } == true) return "Library code"
        if (file.name == "package-info.java" || file.name == "module-info.java") return "No class in this file"
        val classes = file.classes
        if (classes.size != 1) return "Expected exactly one top-level class, interface, enum or record, found ${classes.size}"
        if (classes.single().name == null) return "No class in this file"
        return null
    }

    fun fqn(cls: PsiClass): String = cls.qualifiedName ?: cls.name.orEmpty()

    /** Lombok / record / enum methods have no text of their own to show or edit. */
    private fun isGenerated(method: PsiMethod) = method is LightElement || method is SyntheticElement

    /** The class's own methods as written: no constructors, no generated ones (enum values(), record accessors, Lombok). */
    fun ownMethods(cls: PsiClass): List<PsiMethod> =
        cls.methods.filter { !it.isConstructor && !isGenerated(it) }.sortedBy { it.textRange?.startOffset ?: 0 }

    /**
     * `place`, or `place(Order, boolean)` when the class has more than one `place`. Overloads whose simple-name
     * signatures collide (`save(a.Order)` / `save(b.Order)`) are keyed by qualified type names instead.
     */
    fun methodKey(method: PsiMethod): String {
        val same = method.containingClass?.findMethodsByName(method.name, false)?.filter { !it.isConstructor }.orEmpty()
        if (same.size <= 1) return method.name
        val simple = signatureKey(method, ", ", ::simpleType)
        val collides = same.any { it != method && signatureKey(it, ", ", ::simpleType) == simple }
        return if (collides) signatureKey(method, ", ", ::qualifiedType) else simple
    }

    private fun signatureKey(method: PsiMethod, separator: String, typeName: (PsiType) -> String = ::simpleType): String =
        method.name + method.parameterList.parameters.joinToString(separator, "(", ")") { typeName(it.type) }

    /** `java.util.List<Order>` → `List`; unresolved types keep the name they were written with. */
    private fun simpleType(type: PsiType): String = type.presentableText.replace(Regex("<.*>"), "")

    private fun qualifiedType(type: PsiType): String = type.canonicalText.replace(Regex("<.*>"), "")

    /**
     * [key] as an id or a flow writes it: `place`, `place(Order)`, `place(Order,boolean)` (spaces don't matter).
     * A bare name picks the first in source order. `save(List<Order>)` and `save(com.acme.Order)` match too.
     */
    fun findOwnMethod(cls: PsiClass, key: String): PsiMethod? {
        val wanted = key.replace(" ", "")
        val own = ownMethods(cls)
        if ('(' !in wanted) return own.firstOrNull { it.name == wanted }
        val simple = simpleSignature(wanted)
        return own.firstOrNull { methodKey(it).replace(" ", "") == wanted }
            ?: own.firstOrNull { signatureKey(it, ",") == wanted }
            ?: own.firstOrNull { signatureKey(it, ",") == simple }
    }

    /** `save(List<Order>,com.acme.Order)` → `save(List,Order)`: no type arguments, no packages. */
    private fun simpleSignature(signature: String): String {
        var params = signature.substringAfter('(').removeSuffix(")")
        var previous: String
        do {
            previous = params
            params = params.replace(Regex("<[^<>]*>"), "") // innermost first: Map<String, List<Order>>
        } while (params != previous)
        return signature.substringBefore('(') +
            params.split(',').joinToString(",", "(", ")") { it.replace(Regex("^(\\w+\\.)+(?=\\w)"), "") }
    }

    private fun methodId(method: PsiMethod) = "method:${methodKey(method)}"

    fun methodTitle(method: PsiMethod): String =
        method.name + method.parameterList.parameters.joinToString(", ", "(", ")") { p -> "${p.typeElement?.text ?: p.type.presentableText} ${p.name}" }

    internal fun keyword(cls: PsiClass): String = when {
        cls.isAnnotationType -> "@interface"
        cls.isInterface -> "interface"
        cls.isEnum -> "enum"
        cls.isRecord -> "record"
        else -> "class"
    }

    private fun classTitle(cls: PsiClass): String = buildString {
        append(keyword(cls)).append(' ').append(cls.name)
        val extends = cls.extendsList?.referenceElements.orEmpty().mapNotNull { it.referenceName }
        if (extends.isNotEmpty()) append(" extends ").append(extends.joinToString(", "))
        val implements = cls.implementsList?.referenceElements.orEmpty().mapNotNull { it.referenceName }
        if (implements.isNotEmpty()) append(" implements ").append(implements.joinToString(", "))
    }

    private fun headerTitle(file: PsiJavaFile): String {
        val imports = file.importList?.allImportStatements?.size ?: 0
        val where = if (file.packageName.isEmpty()) "default package" else "package ${file.packageName}"
        return "$where · $imports imports"
    }

    /** Java PSI ranges include the Javadoc and the annotations. */
    fun rangeOf(element: PsiElement): SourceRange = SourceRange(element.textRange.startOffset, element.textRange.endOffset)

    /** Methods [element] calls or refers to (`this::toDto`), constructors excluded. */
    internal fun calledMethods(element: PsiElement): List<PsiMethod> =
        (PsiTreeUtil.findChildrenOfType(element, PsiMethodCallExpression::class.java).mapNotNull { it.resolveMethod() } +
            PsiTreeUtil.findChildrenOfType(element, PsiMethodReferenceExpression::class.java).mapNotNull { it.resolve() as? PsiMethod })
            .filter { !it.isConstructor }

    private fun calls(methods: List<PsiMethod>): List<Link> =
        methods.flatMap { caller ->
            calledMethods(caller)
                .filter { callee -> callee != caller && callee in methods }
                .distinct()
                .map { callee -> Link(LinkKind.CALLS, methodId(caller), methodId(callee)) }
        }

    internal fun classByFqn(context: PsiElement, fqn: String): PsiClass? =
        JavaPsiFacade.getInstance(context.project).findClass(fqn, GlobalSearchScope.allScope(context.project))

    /** Project code only: libraries are never pulled onto the canvas as callees. */
    internal fun inProject(element: PsiElement): Boolean {
        val files = ProjectFileIndex.getInstance(element.project)
        return element.containingFile?.virtualFile?.let { files.isInContent(it) && !files.isInLibrary(it) } == true
    }

    /** True when [cls] only exists as compiled code, so there is nothing to show but its name and members. */
    internal fun isSourceless(cls: PsiClass): Boolean {
        val source = cls.navigationElement
        return source is PsiCompiledElement || source.containingFile?.virtualFile == null
    }
}
