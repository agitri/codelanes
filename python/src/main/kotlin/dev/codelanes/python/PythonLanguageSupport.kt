package dev.codelanes.python

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.jetbrains.python.PythonFileType
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
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

class PythonLanguageSupport : LanguageSupport {
    /** `.py` files only: stubs (`.pyi`) share the Python file type but aren't code to read. */
    override fun handles(file: VirtualFile): Boolean =
        file.extension == "py" && FileTypeRegistry.getInstance().isFileOfType(file, PythonFileType.INSTANCE)

    override fun unsupportedReason(file: PsiFile): String? {
        val virtualFile = file.virtualFile
        return when {
            virtualFile?.extension == "pyi" -> "Stub file"
            virtualFile != null && ProjectFileIndex.getInstance(file.project).isInLibrary(virtualFile) -> "Library code"
            else -> null
        }
    }

    override fun build(file: PsiFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult {
        unsupportedReason(file)?.let { return BuildResult.Unsupported(it) }
        val pyFile = file as? PyFile ?: return BuildResult.Unsupported("Not a Python file")
        return PythonBlockBuilder.build(pyFile, revealed, followedCalls, added)
    }

    override fun methods(project: Project): List<MethodRef> = PythonMethodFinder.find(project)

    override fun routes(project: Project): List<EntryPoint> = PythonRouteFinder.find(project)

    override fun findFlowTarget(project: Project, reference: String): FlowLookup? = PythonFlowLookup.find(project, reference, this)

    override fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean = PythonFlowLookup.proves(kind, from, to)

    override fun newMethodText(name: String, indent: String, inInterface: Boolean): String =
        "${indent}def $name(self):\n$indent    pass\n"

    /**
     * No closing brace to insert before: a module gets `def new_function():` after its last line, or above a closing
     * `if __name__ == "__main__":` block (two blank lines around it, PEP 8); a class gets `def new_method(self):` after
     * its last statement, at the indent its body already uses.
     */
    override fun methodInsertion(text: CharSequence, owner: Block, ownerRange: SourceRange, taken: Set<String>, indent: String): MethodInsertion? {
        val limit = minOf(ownerRange.end, text.length)
        return when (owner.kind) {
            BlockKind.MODULE -> {
                val name = freeName("new_function") { "method:$it" in taken }
                val def = "def $name():\n${indent}pass"
                val guard = PythonSource.mainGuardStart(text, ownerRange.start, limit)
                val end = PythonSource.trimmedEnd(text, ownerRange.start, guard ?: limit)
                when {
                    end == 0 && guard != null -> MethodInsertion(0, "$def\n\n\n", name, "method:$name")
                    end == 0 -> MethodInsertion(0, "$def\n", name, "method:$name")
                    guard != null -> {
                        val newlines = (end until guard).count { text[it] == '\n' }
                        MethodInsertion(end, "\n\n\n$def" + "\n".repeat(maxOf(0, 3 - newlines)), name, "method:$name")
                    }
                    else -> MethodInsertion(end, "\n\n\n$def", name, "method:$name")
                }
            }
            BlockKind.CLASS -> {
                val classKey = owner.id.substringAfterLast('.')
                val name = freeName("new_method") { "method:$classKey.$it" in taken }
                val classIndent = PythonSource.indentOfLine(text, ownerRange.start)
                val methodIndent = PythonSource.headerEnd(text, ownerRange.start, limit)
                    ?.let { PythonSource.bodyIndent(text, it, limit) }
                    ?.takeIf { it.length > classIndent.length && it.startsWith(classIndent) }
                    ?: "$classIndent$indent"
                val unit = methodIndent.removePrefix(classIndent)
                val end = PythonSource.trimmedEnd(text, ownerRange.start, limit)
                MethodInsertion(end, "\n\n${methodIndent}def $name(self):\n$methodIndent${unit}pass", name, "method:$classKey.$name")
            }
            else -> null
        }
    }

    /** A class whose last statement is deleted keeps a body: `pass` at the deleted method's indent. */
    override fun replacementForDeletedMethod(
        text: CharSequence, owner: Block, ownerRange: SourceRange, methodRange: SourceRange, lines: SourceRange,
    ): String {
        if (owner.kind != BlockKind.CLASS) return ""
        val limit = minOf(ownerRange.end, text.length)
        val header = PythonSource.headerEnd(text, ownerRange.start, limit) ?: return ""
        if (header > lines.start) return ""
        val rest = text.subSequence(header, lines.start).toString() + text.subSequence(lines.end, limit)
        if (rest.lines().any { it.substringBefore('#').isNotBlank() }) return ""
        val newline = if (lines.end > lines.start && text[lines.end - 1] == '\n') "\n" else ""
        return PythonSource.indentOfLine(text, methodRange.start) + "pass" + newline
    }

    private fun freeName(base: String, taken: (String) -> Boolean): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { !taken(it) }

    /**
     * Python ids carry the class inside the key: a class (or its constructor) of the open module is `class:<module>.<key>`,
     * one elsewhere `callee:<module>.<Class>::class`; a function `method:<key>` here, `callee:<module>::<key>` elsewhere.
     */
    override fun blockIdOf(target: FlowTarget, openFqn: String): String? {
        val key = target.methodKey
        if (key == null || target.isConstructor) {
            val cls = target.classElement as? PyClass ?: return null
            return if (target.classFqn == openFqn) "class:$openFqn.${PythonBlockBuilder.classKeyOf(cls)}"
            else "callee:${target.classFqn}.${cls.name}::class"
        }
        return if (target.declaringFqn == openFqn) "method:$key" else "callee:${target.declaringFqn}::$key"
    }
}
