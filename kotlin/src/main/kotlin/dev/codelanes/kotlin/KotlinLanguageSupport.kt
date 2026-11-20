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
import dev.codelanes.lang.MethodRef
import dev.codelanes.model.BuildResult
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile

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

    override fun build(file: PsiFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult =
        (file as? KtFile)?.let { KotlinBlockBuilder.build(it, revealed, followedCalls, added) } ?: BuildResult.Unsupported("Not a Kotlin file")

    override fun methods(project: Project): List<MethodRef> = emptyList()

    override fun routes(project: Project): List<EntryPoint> = emptyList()

    override fun findFlowTarget(project: Project, reference: String): FlowLookup? = null

    override fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean = false

    override fun newMethodText(name: String, indent: String, inInterface: Boolean): String =
        if (inInterface) "${indent}fun $name()\n" else "${indent}fun $name() {\n$indent}\n"
}
