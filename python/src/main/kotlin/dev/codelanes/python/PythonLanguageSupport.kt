package dev.codelanes.python

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.jetbrains.python.PythonFileType
import dev.codelanes.lang.EntryPoint
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupport
import dev.codelanes.lang.MethodRef
import dev.codelanes.model.BuildResult

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

    override fun build(file: PsiFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult =
        BuildResult.Unsupported("Not built yet")

    override fun methods(project: Project): List<MethodRef> = emptyList()

    override fun routes(project: Project): List<EntryPoint> = emptyList()

    override fun findFlowTarget(project: Project, reference: String): FlowLookup? = null

    override fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean = false

    override fun newMethodText(name: String, indent: String, inInterface: Boolean): String =
        "${indent}def $name(self):\n$indent    pass\n"
}
