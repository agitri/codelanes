package dev.codelanes.java

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
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
        val methodBlocks = methods.map { Block(methodId(it), BlockKind.METHOD, methodTitle(it), path, rangeOf(it)) }

        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        blocks += Block(HEADER_ID, BlockKind.HEADER, headerTitle(file), path, SourceRange(0, classRange.start), collapsed = true)
        blocks += Block(classId, BlockKind.CLASS, classTitle(cls), path, classRange, excluded = methodBlocks.map { it.range })
        blocks += methodBlocks
        links += methodBlocks.map { Link(LinkKind.OWNS, classId, it.id) }
        // Task 6: related types, overrides, dependencies, revealDeeper
        // Task 7: followCalls, implementedBy
        links += calls(methods)
        return BuildResult.Supported(BlockModel(blocks, links))
    }

    /** Why [file] can't be shown as blocks, or null. Structure only, no resolving: cheap while indexing. */
    fun unsupportedReason(file: PsiFile): String? {
        if (file !is PsiJavaFile) return "Not a Java file"
        if (file.name == "package-info.java" || file.name == "module-info.java") return "No class in this file"
        val classes = file.classes
        if (classes.size != 1) return "Expected exactly one top-level class, interface, enum or record, found ${classes.size}"
        if (classes.single().name == null) return "No class in this file"
        return null
    }

    internal fun fqn(cls: PsiClass): String = cls.qualifiedName ?: cls.name.orEmpty()

    /** The class's own methods as written: no constructors, no generated ones (enum values(), record accessors, Lombok). */
    internal fun ownMethods(cls: PsiClass): List<PsiMethod> =
        cls.methods.filter { !it.isConstructor && it !is LightElement && it !is SyntheticElement }.sortedBy { it.textRange?.startOffset ?: 0 }

    /** `place`, or `place(Order, boolean)` when the class has more than one `place`. */
    internal fun methodKey(method: PsiMethod): String {
        val owner = method.containingClass
        val overloaded = owner != null && ownMethods(owner).count { it.name == method.name } > 1
        return if (overloaded) signatureKey(method, ", ") else method.name
    }

    private fun signatureKey(method: PsiMethod, separator: String): String =
        method.name + method.parameterList.parameters.joinToString(separator, "(", ")") { simpleType(it.type) }

    /** `java.util.List<Order>` → `List`; unresolved types keep the name they were written with. */
    private fun simpleType(type: PsiType): String = type.presentableText.replace(Regex("<.*>"), "")

    /**
     * [key] as an id or a flow writes it: `place`, `place(Order)`, `place(Order,boolean)` (spaces don't matter).
     * A bare name picks the first in source order.
     */
    internal fun findOwnMethod(cls: PsiClass, key: String): PsiMethod? {
        val wanted = key.replace(" ", "")
        val own = ownMethods(cls)
        if ('(' !in wanted) return own.firstOrNull { it.name == wanted }
        return own.firstOrNull { signatureKey(it, ",") == wanted }
    }

    private fun methodId(method: PsiMethod) = "method:${methodKey(method)}"

    internal fun methodTitle(method: PsiMethod): String =
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
    internal fun rangeOf(element: PsiElement): SourceRange = SourceRange(element.textRange.startOffset, element.textRange.endOffset)

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
