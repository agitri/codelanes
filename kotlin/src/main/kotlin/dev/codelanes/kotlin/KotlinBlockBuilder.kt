package dev.codelanes.kotlin

import com.intellij.psi.PsiElement
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
import org.jetbrains.kotlin.psi.KtClassOrObject
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
        val functionKeys = keys(functions.map { it to null })
        functions.forEach { function ->
            val id = "method:${functionKeys.getValue(function)}"
            blocks += Block(id, BlockKind.METHOD, functionTitle(function), path, rangeOf(function))
            if (hasModuleContent) links += Link(LinkKind.OWNS, moduleId, id)
        }
        classes.forEach { cls ->
            val classId = "class:${cls.fqName?.asString() ?: cls.name.orEmpty()}"
            val members = memberFunctions(cls)
            val memberKeys = keys(members.map { it.function to it.owner })
            blocks += Block(classId, BlockKind.CLASS, classTitle(cls), path, rangeOf(cls),
                excluded = members.map { rangeOf(it.function) }.sortedBy { it.start })
            members.forEach { member ->
                val prefix = if (member.owner === cls) "" else "${member.owner.name ?: "Companion"}."
                val id = "method:${cls.name}.$prefix${memberKeys.getValue(member.function)}"
                blocks += Block(id, BlockKind.METHOD, functionTitle(member.function), path, rangeOf(member.function))
                links += Link(LinkKind.OWNS, classId, id)
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

    /** The part of the id after `method:` for a top-level function that is the only one of its name (`save`). */
    fun functionKey(function: KtNamedFunction): String {
        val owner = ownerOf(function)
        val siblings = when (val parent = function.parent) {
            is KtFile -> parent.declarations
            else -> owner?.declarations.orEmpty()
        }.filterIsInstance<KtNamedFunction>().filter { it.name != null }
        val key = keys(siblings.map { it to null })[function] ?: function.name.orEmpty()
        val classKey = topLevelClassOf(function)?.let { cls ->
            val companion = owner?.takeIf { it is KtObjectDeclaration && it.isCompanion() && it != cls }
            listOfNotNull(cls.name, companion?.let { it.name ?: "Companion" })
        }.orEmpty()
        return (classKey + key).joinToString(".")
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

    private fun topLevelClassOf(function: KtNamedFunction): KtClassOrObject? {
        var owner = ownerOf(function)
        while (owner != null && owner.parent !is KtFile) owner = owner.parent?.parent as? KtClassOrObject
        return owner
    }

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

    /** Kotlin PSI ranges of a declaration include its KDoc and annotations. */
    private fun rangeOf(element: PsiElement): SourceRange = SourceRange(element.textRange.startOffset, element.textRange.endOffset)
}
