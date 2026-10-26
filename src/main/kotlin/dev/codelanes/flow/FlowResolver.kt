package dev.codelanes.flow

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.codelanes.lang.FlowLookup
import dev.codelanes.lang.FlowTarget
import dev.codelanes.lang.LanguageSupports

/** One hop between two blocks of the entry file's canvas. [proven]: the code itself shows it, not only the AI. */
data class ResolvedHop(val fromId: String, val toId: String, val kind: String, val proven: Boolean)

/**
 * A flow checked against the code, as block ids on the canvas of [entryFile]. [added] are the `\Fqn::method` keys of
 * the methods that need a block of their own; [unknown] the references that don't exist (their hops are dropped).
 * With an [entryProblem] nothing can be shown.
 */
data class ResolvedFlow(
    val entryFile: VirtualFile?,
    val entryId: String?,
    val entryProblem: String?,
    val hops: List<ResolvedHop>,
    val added: Set<String>,
    val unknown: List<String>,
)

/**
 * Turns the AI's references (`App\Events\OrderPlaced::__construct`) into blocks and decides per hop whether the code
 * proves it. Needs a read action. Draws nothing that isn't in the code.
 */
object FlowResolver {
    fun resolve(project: Project, flow: Flow): ResolvedFlow {
        val unknown = mutableListOf<String>()
        fun lookUp(reference: String): FlowTarget? = when (val found = find(project, reference)) {
            is FlowLookup.Found -> found.target
            is FlowLookup.Missing -> null.also { unknown += found.why }
        }
        val entry = when (val found = find(project, flow.entry.method)) {
            // An entry on `__construct` keeps working: its methodKey is set and its id is the class block.
            is FlowLookup.Found -> found.target.takeIf { it.methodKey != null }
                ?: return ResolvedFlow(null, null, "${flow.entry.method.trim().trimStart('\\')} (not a method)", emptyList(), emptySet(), emptyList())
            is FlowLookup.Missing -> return ResolvedFlow(null, null, found.why, emptyList(), emptySet(), emptyList())
        }
        val entryFile = entry.file
        entry.support.unsupportedReason(entryFile)?.let { reason ->
            return ResolvedFlow(entryFile.virtualFile, null, "${entryFile.name} can't be shown as blocks: $reason", emptyList(), emptySet(), emptyList())
        }
        val openFqn = entry.declaringFqn
        val added = linkedSetOf<String>()
        fun idOf(target: FlowTarget): String = idOf(target, openFqn, added)
        val hops = flow.hops.mapNotNull { hop ->
            val from = lookUp(hop.from)
            val to = lookUp(hop.to)
            if (from == null || to == null) return@mapNotNull null
            val proven = from.support === to.support && from.support.proves(hop.kind, from, to)
            ResolvedHop(idOf(from), idOf(to), hop.kind, proven)
        }
        return ResolvedFlow(entryFile.virtualFile, idOf(entry), null, hops.distinct(), added, unknown.distinct())
    }

    /**
     * The block id of [target] on the canvas of [openFqn]; a block from another file also adds its key to [added].
     * The target's language decides first (Python ids carry the class inside the key).
     */
    internal fun idOf(target: FlowTarget, openFqn: String, added: MutableSet<String>): String {
        target.support.blockIdOf(target, openFqn)?.let { id ->
            if (id.startsWith("callee:")) added += id.removePrefix("callee:")
            return id
        }
        val key = target.methodKey
        if (key == null) {
            if (target.classFqn == openFqn) return "class:$openFqn"
            return "callee:${target.classFqn}::class".also { added += it.removePrefix("callee:") }
        }
        if (target.declaringFqn == openFqn) return if (target.isConstructor) "class:$openFqn" else "method:$key"
        return "callee:${target.declaringFqn}::$key".also { added += it.removePrefix("callee:") }
    }

    /** The first language that finds the reference wins; if none finds it, the first one's reason is reported. */
    private fun find(project: Project, reference: String): FlowLookup {
        val trimmed = reference.trim()
        if ("::" !in trimmed) return FlowLookup.Missing("$trimmed (not a Class::method reference)")
        val answers = LanguageSupports.all().mapNotNull { it.findFlowTarget(project, trimmed) }
        return answers.firstOrNull { it is FlowLookup.Found } ?: answers.firstOrNull()
            ?: FlowLookup.Missing("${trimmed.trimStart('\\')} (not a Class::method reference)")
    }
}
