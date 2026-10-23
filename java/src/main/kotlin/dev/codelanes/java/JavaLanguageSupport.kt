package dev.codelanes.java

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.codelanes.lang.EntryPoint
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupport
import dev.codelanes.lang.MethodRef
import dev.codelanes.model.BuildResult

class JavaLanguageSupport : LanguageSupport {
    override fun handles(file: VirtualFile): Boolean = FileTypeRegistry.getInstance().isFileOfType(file, JavaFileType.INSTANCE)

    override fun unsupportedReason(file: PsiFile): String? = JavaBlockBuilder.unsupportedReason(file)

    override fun build(file: PsiFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult =
        JavaBlockBuilder.build(file, revealed, followedCalls, added)

    override fun methods(project: Project): List<MethodRef> = JavaMethodFinder.find(project)

    /** Spring MVC routes, see [JavaRouteFinder]. */
    override fun routes(project: Project): List<EntryPoint> = JavaRouteFinder.find(project)

    override fun findFlowTarget(project: Project, reference: String): FlowLookup? = JavaFlowLookup.find(project, reference, this)

    override fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean = JavaFlowLookup.proves(kind, from, to)

    override fun newMethodText(name: String, indent: String, inInterface: Boolean): String =
        if (inInterface) "${indent}void $name();\n"
        else "${indent}public void $name() {\n$indent}\n"
}
