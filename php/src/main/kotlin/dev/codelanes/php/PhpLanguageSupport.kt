package dev.codelanes.php

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.jetbrains.php.lang.PhpFileType
import dev.codelanes.lang.EntryPoint
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupport
import dev.codelanes.lang.MethodRef
import dev.codelanes.model.BuildResult

class PhpLanguageSupport : LanguageSupport {
    override fun handles(file: VirtualFile): Boolean = FileTypeRegistry.getInstance().isFileOfType(file, PhpFileType.INSTANCE)

    override fun unsupportedReason(file: PsiFile): String? = PhpBlockBuilder.unsupportedReason(file)

    override fun build(file: PsiFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult =
        PhpBlockBuilder.build(file, revealed, followedCalls, added)

    override fun methods(project: Project): List<MethodRef> = MethodFinder.find(project)

    override fun routes(project: Project): List<EntryPoint> = RouteFinder.find(project)

    override fun findFlowTarget(project: Project, reference: String): FlowLookup? = PhpFlowLookup.find(project, reference, this)

    override fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean = PhpFlowLookup.proves(kind, from, to)

    override fun newMethodText(name: String, indent: String, inInterface: Boolean): String =
        if (inInterface) "${indent}public function $name(): void;\n"
        else "${indent}public function $name(): void\n$indent{\n$indent}\n"
}
