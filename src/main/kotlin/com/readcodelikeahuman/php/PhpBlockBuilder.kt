package com.readcodelikeahuman.php

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.lang.psi.PhpFile
import com.jetbrains.php.lang.psi.elements.ClassReference
import com.jetbrains.php.lang.psi.elements.Method
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.PhpNamedElement
import com.jetbrains.php.lang.psi.elements.PhpNamespace
import com.jetbrains.php.lang.psi.elements.PhpTypeDeclaration
import com.jetbrains.php.lang.psi.elements.PhpUse
import com.readcodelikeahuman.model.Block
import com.readcodelikeahuman.model.BlockKind
import com.readcodelikeahuman.model.BlockModel
import com.readcodelikeahuman.model.BuildResult
import com.readcodelikeahuman.model.Link
import com.readcodelikeahuman.model.LinkKind
import com.readcodelikeahuman.model.SourceRange
import com.jetbrains.php.lang.psi.elements.Function as PhpFunction

/** Turns a single-type PHP file into a [BlockModel]; anything else is [BuildResult.Unsupported]. */
object PhpBlockBuilder {
    const val HEADER_ID = "header"

    fun build(file: PsiFile): BuildResult {
        if (file !is PhpFile) return BuildResult.Unsupported("Not a PHP file")

        val classes = PsiTreeUtil.findChildrenOfType(file, PhpClass::class.java).filterNot { it.isAnonymous }
        if (classes.size != 1) {
            return BuildResult.Unsupported("Expected exactly one class, interface, trait or enum, found ${classes.size}")
        }
        val phpClass = classes.single()

        val functions = PsiTreeUtil.findChildrenOfType(file, PhpFunction::class.java)
            .filter { it !is Method && !it.isClosure }
        if (functions.isNotEmpty()) return BuildResult.Unsupported("File contains top-level functions")

        val classRange = rangeWithDoc(phpClass)
        if (file.text.substring(classRange.end).isNotBlank()) {
            return BuildResult.Unsupported("File contains code after the ${keyword(phpClass)}")
        }

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
        relatedTypes(phpClass).forEach { (block, linkKind) ->
            blocks += block
            links += Link(linkKind, block.id, classId)
        }
        dependencies(phpClass).forEach { block ->
            blocks += block
            links += Link(LinkKind.INJECTS, block.id, classId)
        }

        return BuildResult.Supported(BlockModel(blocks, links))
    }

    private fun externalBlock(kind: BlockKind, target: PhpClass): Block = Block(
        id = "${kind.name.lowercase()}:${target.fqn}",
        kind = kind,
        title = "${keyword(target)} ${target.name}",
        filePath = target.containingFile.virtualFile.path,
        range = rangeWithDoc(target),
        collapsed = true,
    )

    private fun resolved(references: List<ClassReference>): List<PhpClass> =
        references.mapNotNull { it.resolve() as? PhpClass }.distinctBy { it.fqn }

    /** Related types in left-column order, each with the link kind pointing into the class block. */
    private fun relatedTypes(phpClass: PhpClass): List<Pair<Block, LinkKind>> {
        val parentKind = if (phpClass.isInterface) BlockKind.INTERFACE else BlockKind.PARENT
        return resolved(phpClass.implementsList.referenceElements).map { externalBlock(BlockKind.INTERFACE, it) to LinkKind.IMPLEMENTS } +
            resolved(phpClass.extendsList.referenceElements).map { externalBlock(parentKind, it) to LinkKind.EXTENDS } +
            phpClass.traits.distinctBy { it.fqn }.map { externalBlock(BlockKind.TRAIT, it) to LinkKind.USES }
    }

    private fun dependencies(phpClass: PhpClass): List<Block> {
        val constructor = phpClass.ownMethods.firstOrNull { it.name.equals("__construct", ignoreCase = true) }
            ?: return emptyList()
        val typeReferences = constructor.parameters.flatMap { parameter ->
            PsiTreeUtil.findChildrenOfType(parameter, ClassReference::class.java)
                .filter { PsiTreeUtil.getParentOfType(it, PhpTypeDeclaration::class.java) != null }
        }
        return resolved(typeReferences).map { externalBlock(BlockKind.DEPENDENCY, it) }
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
