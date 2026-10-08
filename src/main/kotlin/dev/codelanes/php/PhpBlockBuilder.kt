package dev.codelanes.php

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.Declare
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpNamespace
import com.jetbrains.php.lang.psi.elements.PhpNamespaceReference
import com.jetbrains.php.lang.psi.elements.PhpTypeDeclaration
import com.jetbrains.php.lang.psi.elements.PhpUse
import com.jetbrains.php.lang.psi.elements.PhpUseList
import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.BuildResult
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
import dev.codelanes.model.SourceRange
import com.jetbrains.php.lang.psi.elements.Function as PhpFunction

/** Turns a single-type PHP file into a [BlockModel]; anything else is [BuildResult.Unsupported]. */
object PhpBlockBuilder {
    const val HEADER_ID = "header"
    const val MORE_ID = "more:implementers"
    const val MAX_IMPLEMENTERS = 10

    /** Leaf tokens allowed in the header besides whitespace and comments. */
    private val HEADER_TOKENS = setOf("<?php", "<?", "namespace", ";", "{")

    fun build(file: PsiFile, revealed: Set<String> = emptySet()): BuildResult {
        unsupportedReason(file)?.let { return BuildResult.Unsupported(it) }
        val phpClass = singleClass(file as PhpFile)
        val classRange = rangeWithDoc(phpClass)

        val path = file.virtualFile.path
        val classId = "class:${phpClass.fqn}"
        val methods = phpClass.ownMethods
            .filterNot { it.name.equals("__construct", ignoreCase = true) }
            .sortedBy { it.textRange.startOffset }
        val methodBlocks = methods.map { method ->
            Block(
                id = "method:${method.name}",
                kind = BlockKind.METHOD,
                title = methodTitle(method),
                filePath = path,
                range = rangeWithDoc(method),
            )
        }

        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        blocks += Block(
            id = HEADER_ID,
            kind = BlockKind.HEADER,
            title = headerTitle(file),
            filePath = path,
            range = SourceRange(0, classRange.start),
            collapsed = true,
        )
        blocks += Block(
            id = classId,
            kind = BlockKind.CLASS,
            title = classTitle(phpClass),
            filePath = path,
            range = classRange,
            excluded = methodBlocks.map { it.range },
        )
        blocks += methodBlocks
        links += methodBlocks.map { Link(LinkKind.OWNS, classId, it.id) }
        val related = relatedTypes(phpClass)
        related.forEach { (block, linkKind, _) ->
            blocks += block
            links += Link(linkKind, block.id, classId)
        }
        links += overrides(methods, related)
        revealDeeper(related, revealed, classId).let { (deeperBlocks, deeperLinks) ->
            blocks += deeperBlocks
            links += deeperLinks
        }
        implementedBy(phpClass, classId, methods, path).let { (implBlocks, implLinks) ->
            blocks += implBlocks
            links += implLinks
        }
        dependencies(phpClass).forEach { block ->
            blocks += block
            links += Link(LinkKind.INJECTS, block.id, classId)
        }
        links += calls(phpClass, methods)

        return BuildResult.Supported(BlockModel(blocks, links))
    }

    /**
     * Why [file] can't be shown as blocks, or null if it can. Structure only: no reference resolving,
     * so it is cheap and safe while the IDE is indexing.
     */
    fun unsupportedReason(file: PsiFile): String? {
        if (file !is PhpFile) return "Not a PHP file"

        val classes = PsiTreeUtil.findChildrenOfType(file, PhpClass::class.java).filterNot { it.isAnonymous }
        if (classes.size != 1) return "Expected exactly one class, interface, trait or enum, found ${classes.size}"
        val phpClass = classes.single()

        val functions = PsiTreeUtil.findChildrenOfType(file, PhpFunction::class.java)
            .filter { it !is Method && !it.isClosure }
        if (functions.isNotEmpty()) return "File contains top-level functions"

        val classRange = rangeWithDoc(phpClass)
        if (file.text.substring(classRange.end).isNotBlank()) return "File contains code after the ${keyword(phpClass)}"
        if (hasCodeBefore(file, classRange.start)) return "File contains code before the ${keyword(phpClass)}"
        phpClass.ownMethods.groupBy { it.name.lowercase() }.values.firstOrNull { it.size > 1 }?.let {
            return "Duplicate method name ${it.first().name}"
        }
        return null
    }

    private fun singleClass(file: PhpFile): PhpClass =
        PsiTreeUtil.findChildrenOfType(file, PhpClass::class.java).single { !it.isAnonymous }

    private fun externalBlock(kind: BlockKind, target: PhpClass): Block = Block(
        id = "${kind.name.lowercase()}:${target.fqn}",
        kind = kind,
        title = "${keyword(target)} ${target.name}",
        filePath = target.containingFile.virtualFile.path,
        range = rangeWithDoc(target),
        collapsed = true,
        summary = target.ownMethods.sortedBy { it.textRange.startOffset }.map(::methodTitle),
    )

    private fun resolved(references: List<ClassReference>): List<PhpClass> =
        references.mapNotNull { it.resolve() as? PhpClass }.distinctBy { it.fqn }

    private data class Related(val block: Block, val linkKind: LinkKind, val type: PhpClass)

    /** Related types in left-column order, each with the link kind pointing into the class block. */
    private fun relatedTypes(phpClass: PhpClass): List<Related> {
        val parentKind = if (phpClass.isInterface) BlockKind.INTERFACE else BlockKind.PARENT
        return resolved(phpClass.implementsList.referenceElements).map { Related(externalBlock(BlockKind.INTERFACE, it), LinkKind.IMPLEMENTS, it) } +
            resolved(phpClass.extendsList.referenceElements).map { Related(externalBlock(parentKind, it), LinkKind.EXTENDS, it) } +
            phpClass.traits.distinctBy { it.fqn }.map { Related(externalBlock(BlockKind.TRAIT, it), LinkKind.USES, it) }
    }

    /**
     * For an interface, trait or abstract class: the project classes that implement / use / extend it (at most
     * [MAX_IMPLEMENTERS], alphabetical, plus a "more" block), and per class only the methods that implement or
     * override one of this type's methods.
     */
    private fun implementedBy(phpClass: PhpClass, classId: String, methods: List<Method>, path: String): Pair<List<Block>, List<Link>> {
        if (!phpClass.isInterface && !phpClass.isTrait && !phpClass.isAbstract) return emptyList<Block>() to emptyList()
        val index = PhpIndex.getInstance(phpClass.project)
        val files = ProjectFileIndex.getInstance(phpClass.project)
        val candidates = (if (phpClass.isTrait) index.getTraitUsages(phpClass) else index.getDirectSubclasses(phpClass.fqn))
            .filter { !it.isInterface && it.fqn != phpClass.fqn }
            .filter { c -> c.containingFile?.virtualFile?.let { files.isInContent(it) && !files.isInLibrary(it) } == true }
            .distinctBy { it.fqn }
            .sortedWith(compareBy({ it.name }, { it.fqn }))
        val blocks = mutableListOf<Block>()
        val links = mutableListOf<Link>()
        for (implementer in candidates.take(MAX_IMPLEMENTERS)) {
            val block = externalBlock(BlockKind.IMPLEMENTER, implementer)
            blocks += block
            links += Link(LinkKind.IMPLEMENTED_BY, classId, block.id)
            for (method in methods) {
                val implementation = implementer.findOwnMethodByName(method.name) ?: continue
                val id = "implementation:${implementer.fqn}::${method.name}"
                blocks += Block(
                    id = id,
                    kind = BlockKind.IMPLEMENTATION,
                    title = "${implementer.name}::${methodTitle(implementation)}",
                    filePath = implementer.containingFile.virtualFile.path,
                    range = rangeWithDoc(implementation),
                    collapsed = true,
                )
                links += Link(LinkKind.OWNS, block.id, id)
                links += Link(LinkKind.OVERRIDES, id, "method:${method.name}", rangeWithDoc(method))
            }
        }
        val rest = candidates.drop(MAX_IMPLEMENTERS)
        if (rest.isNotEmpty()) {
            blocks += Block(MORE_ID, BlockKind.MORE, "and ${rest.size} more", path, SourceRange(0, 0), collapsed = true, summary = rest.map { it.name })
            links += Link(LinkKind.IMPLEMENTED_BY, classId, MORE_ID)
        }
        return blocks to links
    }

    /**
     * For every revealed related block: its own parents, interfaces and traits (and theirs, if revealed too),
     * linked into the revealed block. A type reached twice gets one block with several links.
     */
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

    /** A method links to each related type that declares a method with the same name (it implements or overrides it). */
    private fun overrides(methods: List<Method>, related: List<Related>): List<Link> =
        methods.flatMap { method ->
            related.mapNotNull { r ->
                r.type.findOwnMethodByName(method.name)?.let { overridden ->
                    Link(LinkKind.OVERRIDES, "method:${method.name}", r.block.id, rangeWithDoc(overridden))
                }
            }
        }

    private fun dependencies(phpClass: PhpClass): List<Block> {
        val constructor = phpClass.ownMethods.firstOrNull { it.name.equals("__construct", ignoreCase = true) }
            ?: return emptyList()
        val typeReferences = constructor.parameters.flatMap { parameter ->
            PsiTreeUtil.findChildrenOfType(parameter, ClassReference::class.java)
                .filter { PsiTreeUtil.getParentOfType(it, PhpTypeDeclaration::class.java) != null }
        }
        return resolved(typeReferences)
            .filter { it.fqn != phpClass.fqn }
            .map { externalBlock(BlockKind.DEPENDENCY, it) }
    }

    private fun calls(phpClass: PhpClass, methods: List<Method>): List<Link> =
        methods.flatMap { caller ->
            PsiTreeUtil.findChildrenOfType(caller, MethodReference::class.java)
                .mapNotNull { it.resolve() as? Method }
                .filter { callee -> callee != caller && callee in methods && callee.containingClass == phpClass }
                .distinct()
                .map { callee -> Link(LinkKind.CALLS, "method:${caller.name}", "method:${callee.name}") }
        }

    /** True if anything other than declare/namespace/use/comments precedes [classStart] (recursing into the namespace). */
    private fun hasCodeBefore(element: PsiElement, classStart: Int): Boolean {
        for (child in generateSequence(element.firstChild) { it.nextSibling }) {
            if (child.textRange.startOffset >= classStart) break
            if (child.textRange.endOffset > classStart) return hasCodeBefore(child, classStart)
            val allowed = child is PsiWhiteSpace || child is PsiComment || child is PhpUseList || child is Declare ||
                child is PhpNamespaceReference || (child.firstChild == null && (child.parent is PhpNamespace || child.text.trim() in HEADER_TOKENS))
            if (!allowed) return true
        }
        return false
    }

    internal fun rangeWithDoc(element: PhpNamedElement): SourceRange {
        val start = minOf(element.textRange.startOffset, element.docComment?.textRange?.startOffset ?: Int.MAX_VALUE)
        return SourceRange(start, element.textRange.endOffset)
    }

    internal fun keyword(phpClass: PhpClass): String = when {
        phpClass.isInterface -> "interface"
        phpClass.isTrait -> "trait"
        phpClass.isEnum -> "enum"
        else -> "class"
    }

    private fun classTitle(phpClass: PhpClass): String = buildString {
        append(keyword(phpClass)).append(' ').append(phpClass.name)
        val extends = phpClass.extendsList.referenceElements.mapNotNull { it.name }
        if (extends.isNotEmpty()) append(" extends ").append(extends.joinToString(", "))
        val implements = phpClass.implementsList.referenceElements.mapNotNull { it.name }
        if (implements.isNotEmpty()) append(" implements ").append(implements.joinToString(", "))
    }

    private fun methodTitle(method: Method): String =
        method.name + method.parameters.joinToString(", ", "(", ")") { it.text.substringBefore('=').trim() }

    private fun headerTitle(file: PhpFile): String {
        val namespace = PsiTreeUtil.findChildOfType(file, PhpNamespace::class.java)?.fqn?.trimStart('\\')
        val imports = PsiTreeUtil.findChildrenOfType(file, PhpUse::class.java).count { !it.isTraitImport }
        val where = if (namespace.isNullOrEmpty()) "global namespace" else "namespace $namespace"
        return "$where · $imports imports"
    }
}
