package dev.codelanes.lang

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.codelanes.model.Block
import dev.codelanes.model.BuildResult
import dev.codelanes.model.SourceRange

/**
 * Everything CodeLanes needs to know about one language. Registered from a content module
 * (`codelanes.php`, `codelanes.java`), so each one only loads in an IDE that has the language.
 */
interface LanguageSupport {
    /** Cheap check on the file type only, no PSI: is this file this language's? */
    fun handles(file: VirtualFile): Boolean

    /** Why [file] can't be shown as blocks, or null. Structure only, safe while indexing. */
    fun unsupportedReason(file: PsiFile): String?

    fun build(file: PsiFile, revealed: Set<String>, followedCalls: Set<String>, added: Set<String>): BuildResult

    /** Every method in the project's own code, for Add method… Needs a read action and indexes. */
    /**
     * True when [build] runs analysis the platform refuses on the EDT (Kotlin K2: DefinitionsScopedSearch throws
     * ProhibitedAnalysisException there). The session's synchronous rebuild then computes in a cancellable background
     * read action while the EDT waits in a modal progress.
     */
    val buildsInBackground: Boolean get() = false

    fun methods(project: Project): List<MethodRef>

    /** Routes found without AI, for Follow a Request. */
    fun routes(project: Project): List<EntryPoint>

    /** A flow reference (`Fqn::method`) → what it points at; null if it isn't written in this language's syntax. */
    fun findFlowTarget(project: Project, reference: String): FlowLookup?

    /** Supports in one family resolve to the same PSI and prove each other's hops ("jvm": Java, Kotlin). */
    val proofFamily: String? get() = null

    /** Whether the code proves a hop of [kind] between two targets this support found. */
    fun proves(kind: String, from: FlowTarget, to: FlowTarget): Boolean

    /** Text of an empty method called [name], indented by [indent], ending in a newline, for "+ method". */
    fun newMethodText(name: String, indent: String, inInterface: Boolean): String

    /**
     * What "+ method" inserts into [owner] (a CLASS or MODULE block, currently spanning [ownerRange] of [text]), or null
     * to use the brace rule with [newMethodText]. [taken]: ids of the METHOD blocks on the canvas. [indent]: one level.
     */
    fun methodInsertion(text: CharSequence, owner: Block, ownerRange: SourceRange, taken: Set<String>, indent: String): MethodInsertion? = null

    /**
     * What replaces [lines] (the whole lines of the method at [methodRange], about to be deleted from [owner]) so the
     * code stays valid, e.g. `pass` for a Python class left empty. Empty: just delete them.
     */
    fun replacementForDeletedMethod(text: CharSequence, owner: Block, ownerRange: SourceRange, methodRange: SourceRange, lines: SourceRange): String = ""

    /** The block id [target] gets on a canvas opened on [openFqn], or null for the default rule. */
    fun blockIdOf(target: FlowTarget, openFqn: String): String? = null
}

/**
 * "+ method": replace the [replaced] characters at [offset] (usually none) with [text]; the new method is called [name]
 * and gets block [blockId].
 */
data class MethodInsertion(val offset: Int, val text: String, val name: String, val blockId: String, val replaced: Int = 0)

object LanguageSupports {
    private val EP_NAME = ExtensionPointName.create<LanguageSupport>("dev.codelanes.languageSupport")

    fun all(): List<LanguageSupport> = EP_NAME.extensionList

    fun forFile(file: VirtualFile): LanguageSupport? = all().firstOrNull { it.handles(file) }
}

/** A method that can be dropped on a canvas, e.g. "OrderService::place(string $customer)". */
data class MethodRef(val label: String, val classFqn: String, val method: String)

/** A route found in the code: its label (e.g. "POST /api/orders") and the controller method it lands in. */
data class EntryPoint(val label: String, val filePath: String, val methodName: String)
