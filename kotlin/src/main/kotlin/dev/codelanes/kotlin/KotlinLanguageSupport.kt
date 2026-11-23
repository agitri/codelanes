package dev.codelanes.kotlin

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.codelanes.lang.EntryPoint
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupport
import dev.codelanes.lang.MethodInsertion
import dev.codelanes.lang.MethodRef
import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BuildResult
import dev.codelanes.model.SourceRange
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

class KotlinLanguageSupport : LanguageSupport {
    /** `.kt` files only: scripts (`.kts`) share the Kotlin file type but aren't supported. */
    override fun handles(file: VirtualFile): Boolean =
        file.extension == "kt" && FileTypeRegistry.getInstance().isFileOfType(file, KotlinFileType.INSTANCE)

    override fun unsupportedReason(file: PsiFile): String? {
        val virtualFile = file.virtualFile
        return when {
            virtualFile?.extension == "kts" -> "Kotlin scripts aren't supported yet"
            virtualFile != null && ProjectFileIndex.getInstance(file.project).isInLibrary(virtualFile) -> "Library code"
            else -> null
        }
    }

    override val buildsInBackground: Boolean get() = true

    override fun build(file: PsiFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult =
        (file as? KtFile)?.let { KotlinBlockBuilder.build(it, revealed, followedCalls, added) } ?: BuildResult.Unsupported("Not a Kotlin file")

    override fun methods(project: Project): List<MethodRef> = KotlinMethodFinder.find(project)

    override fun routes(project: Project): List<EntryPoint> = KotlinRouteFinder.find(project)

    override fun findFlowTarget(project: Project, reference: String): FlowLookup? = KotlinFlowLookup.find(project, reference, this)

    override fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean = KotlinFlowLookup.proves(kind, from, to)

    override val proofFamily: String get() = "jvm"

    override fun newMethodText(name: String, indent: String, inInterface: Boolean): String =
        if (inInterface) "${indent}fun $name()\n" else "${indent}fun $name() {\n$indent}\n"

    /**
     * Kotlin class method ids are `method:<Class>.<name>`, so the brace rule's `method:<name>` doesn't fit: a module gets
     * `fun newFunction()` after its last line; a class gets `fun newMethod()` before its closing brace, or a body of its
     * own when it has none (`class C(val x: Int)`).
     */
    override fun methodInsertion(text: CharSequence, owner: Block, ownerRange: SourceRange, taken: Set<String>, indent: String): MethodInsertion? =
        when (owner.kind) {
            BlockKind.MODULE -> {
                val name = freeName("newFunction") { "method:$it" in taken }
                val blankLines = text.takeLastWhile { it == '\n' }.length
                val prefix = if (text.isEmpty()) "" else "\n".repeat(2 - minOf(blankLines, 2))
                MethodInsertion(text.length, prefix + newMethodText(name, "", false), name, "method:$name")
            }
            BlockKind.CLASS -> classInsertion(text, owner, ownerRange, taken, indent)
            else -> null
        }

    private fun classInsertion(text: CharSequence, owner: Block, ownerRange: SourceRange, taken: Set<String>, indent: String): MethodInsertion? {
        val className = owner.id.removePrefix("class:").substringAfterLast('.')
        val name = freeName("newMethod") { "method:$className.$it" in taken }
        val blockId = "method:$className.$name"
        val method = newMethodText(name, indent, INTERFACE_TITLE.containsMatchIn(owner.title))
        var end = minOf(ownerRange.end, text.length)
        while (end > ownerRange.start && text[end - 1].isWhitespace()) end--
        if (end <= ownerRange.start) return null
        // No body: give the class one.
        if (text[end - 1] != '}') return MethodInsertion(end, " {\n$method}", name, blockId)
        // The range can have grown past its brace (text typed after it): find the brace itself.
        val brace = text.lastIndexOf('}', end - 1)
        if (brace < ownerRange.start) return null
        val lineStart = lineStartOf(text, brace)
        val onItsOwnLine = lineStart < brace && text.subSequence(lineStart, brace).isBlank() || lineStart == brace
        val at = if (onItsOwnLine) lineStart else brace
        var before = at
        while (before > ownerRange.start && text[before - 1].isWhitespace()) before--
        val opensBody = onItsOwnLine && before > ownerRange.start && text[before - 1] == '{'
        val insertion = MethodInsertion(at, (if (opensBody) "" else "\n") + method, name, blockId)
        return if (owner.title.startsWith("enum class ")) closingEntries(text, ownerRange.start, brace, insertion) else insertion
    }

    /**
     * An enum's members must follow a `;` after its entries (`enum class E { A, B }`): when the body has entries but no
     * `;` of its own, [insertion] also puts one right after the last entry (before a comment on that line).
     */
    private fun closingEntries(text: CharSequence, start: Int, brace: Int, insertion: MethodInsertion): MethodInsertion {
        var open = -1
        scanCode(text, start, brace) { i, c, depth -> if (open < 0 && c == '{' && depth == 0) open = i }
        if (open < 0) return insertion
        var lastCode = -1
        var hasSemicolon = false
        scanCode(text, open + 1, brace) { i, c, depth ->
            if (!c.isWhitespace()) lastCode = i
            if (c == ';' && depth == 0) hasSemicolon = true
        }
        if (hasSemicolon || lastCode < 0 || lastCode + 1 > insertion.offset) return insertion
        val after = lastCode + 1
        val between = text.subSequence(after, insertion.offset)
        return insertion.copy(offset = after, text = ";$between${insertion.text}", replaced = insertion.offset - after)
    }

    /**
     * Calls [visit] for each character of code in [from, to): comments and the inside of string and character literals
     * are skipped; depth counts the brackets opened since [from] (`{` and `(` themselves are at the outer depth).
     */
    private fun scanCode(text: CharSequence, from: Int, to: Int, visit: (Int, Char, Int) -> Unit) {
        var i = from
        var depth = 0
        fun startsWith(s: String) = i + s.length <= to && text.subSequence(i, i + s.length).toString() == s
        while (i < to) {
            when {
                startsWith("//") -> while (i < to && text[i] != '\n') i++
                startsWith("/*") -> {
                    val end = text.indexOf("*/", i + 2)
                    i = if (end < 0 || end + 2 > to) to else end + 2
                }
                startsWith("\"\"\"") -> {
                    val end = text.indexOf("\"\"\"", i + 3)
                    i = if (end < 0 || end + 3 > to) to else end + 3
                }
                text[i] == '"' || text[i] == '\'' -> {
                    val quote = text[i]
                    visit(i, quote, depth)
                    i++
                    while (i < to && text[i] != quote) i += if (text[i] == '\\') 2 else 1
                    if (i < to) visit(i, quote, depth)
                    i++
                }
                else -> {
                    val c = text[i]
                    if (c == '}' || c == ')' || c == ']') depth--
                    visit(i, c, depth)
                    if (c == '{' || c == '(' || c == '[') depth++
                    i++
                }
            }
        }
    }

    private fun lineStartOf(text: CharSequence, offset: Int): Int {
        var i = offset
        while (i > 0 && text[i - 1] != '\n') i--
        return i
    }

    private fun freeName(base: String, taken: (String) -> Boolean): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { !taken(it) }

    /**
     * `class:<fqn>` / `callee:<fqn>::class` for a class or constructor, `method:<key>` / `callee:<owner fqn>::<short key>`
     * for a function. A target is in the open file when it is declared in [openFile] (another class or a top-level
     * function of that file included; a member inherited from another file not). Without [openFile]: when its declaring
     * owner or its file's module is [openFqn].
     */
    override fun blockIdOf(target: FlowTarget, openFqn: String): String? = blockIdOf(target, openFqn, null)

    override fun blockIdOf(target: FlowTarget, openFqn: String, openFile: PsiFile?): String? {
        val key = target.methodKey
        val inOpenFile = if (openFile != null) target.file.originalFile == openFile.originalFile
        else target.declaringFqn == openFqn || (target.file as? KtFile)?.let(KotlinBlockBuilder::moduleFqn) == openFqn
        if (key == null || target.isConstructor) {
            return if (inOpenFile) "class:${target.classFqn}" else "callee:${target.classFqn}::class"
        }
        if (inOpenFile) return "method:$key"
        val short = (target.methodElement as? KtNamedFunction)?.let(KotlinBlockBuilder::calleeKey)?.substringAfter("::")
            ?: if (target.classElement is KtClassOrObject) key.removePrefix(target.classFqn.substringAfterLast('.') + ".") else key
        return "callee:${target.declaringFqn}::$short"
    }

    /** Kotlin classes and functions on a Java canvas (Java sees their light classes; this gets their Kotlin source). */
    override fun externalBlockFor(element: PsiElement, kind: BlockKind): Block? = KotlinBlockBuilder.foreignBlock(element, kind)

    /** `callee:acme.Repo::save`; a top-level function, even when called through its `NoteScreenKt` facade: `callee:acme.NoteScreen::format`. */
    override fun calleeIdFor(element: PsiElement): String? =
        (element as? KtNamedFunction)?.let(KotlinBlockBuilder::calleeKey)?.let { "callee:$it" }

    override fun elementForKey(project: Project, key: String): PsiElement? = KotlinBlockBuilder.kotlinElementForKey(project, key)

    private companion object {
        val INTERFACE_TITLE = Regex("^(sealed |fun )?interface ")
    }
}
