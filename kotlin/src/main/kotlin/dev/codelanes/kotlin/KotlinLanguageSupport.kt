package dev.codelanes.kotlin

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
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

    override fun findFlowTarget(project: Project, reference: String): FlowLookup? = null

    override fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean = false

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
        return MethodInsertion(at, (if (opensBody) "" else "\n") + method, name, blockId)
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
     * for a function. A target is in the open file when its class, its declaring owner or its file's module is [openFqn].
     */
    override fun blockIdOf(target: FlowTarget, openFqn: String): String? {
        val key = target.methodKey
        val inOpenFile = target.classFqn == openFqn || target.declaringFqn == openFqn ||
            (target.file as? KtFile)?.let(KotlinBlockBuilder::moduleFqn) == openFqn
        if (key == null || target.isConstructor) {
            return if (inOpenFile) "class:${target.classFqn}" else "callee:${target.classFqn}::class"
        }
        if (inOpenFile) return "method:$key"
        val short = (target.methodElement as? KtNamedFunction)?.let(KotlinBlockBuilder::calleeKey)?.substringAfter("::")
            ?: if (target.classElement is KtClassOrObject) key.removePrefix(target.classFqn.substringAfterLast('.') + ".") else key
        return "callee:${target.declaringFqn}::$short"
    }

    private companion object {
        val INTERFACE_TITLE = Regex("^(sealed |fun )?interface ")
    }
}
