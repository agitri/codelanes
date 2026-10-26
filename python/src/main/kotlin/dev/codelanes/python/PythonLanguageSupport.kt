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

    override fun routes(project: Project): List<EntryPoint> = emptyList()

    override fun findFlowTarget(project: Project, reference: String): FlowLookup? = null

    override fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean = false

    override fun newMethodText(name: String, indent: String, inInterface: Boolean): String =
        "${indent}def $name(self):\n$indent    pass\n"

    /**
     * No closing brace to insert before: a module gets `def new_function():` after its last line (two blank lines
     * above, PEP 8), a class `def new_method(self):` after its last statement, one level deeper than the class.
     */
    override fun methodInsertion(text: CharSequence, owner: Block, ownerRange: SourceRange, taken: Set<String>, indent: String): MethodInsertion? {
        var end = minOf(ownerRange.end, text.length)
        while (end > ownerRange.start && text[end - 1].isWhitespace()) end--
        return when (owner.kind) {
            BlockKind.MODULE -> {
                val name = freeName("new_function") { "method:$it" in taken }
                val def = "def $name():\n${indent}pass"
                MethodInsertion(end, if (end == 0) "$def\n" else "\n\n\n$def", name, "method:$name")
            }
            BlockKind.CLASS -> {
                val classKey = owner.id.substringAfterLast('.')
                val name = freeName("new_method") { "method:$classKey.$it" in taken }
                val lineStart = text.lastIndexOf('\n', ownerRange.start - 1) + 1
                val classIndent = text.subSequence(lineStart, ownerRange.start).takeWhile { it == ' ' || it == '\t' }
                val methodIndent = "$classIndent$indent"
                MethodInsertion(end, "\n\n${methodIndent}def $name(self):\n$methodIndent${indent}pass", name, "method:$classKey.$name")
            }
            else -> null
        }
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
