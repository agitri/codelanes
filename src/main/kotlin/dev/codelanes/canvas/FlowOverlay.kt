package dev.codelanes.canvas

import dev.codelanes.flow.ResolvedHop
import dev.codelanes.model.Block
import dev.codelanes.model.BlockKind
import dev.codelanes.model.BlockModel
import dev.codelanes.model.Link
import dev.codelanes.model.LinkKind
import dev.codelanes.model.SourceRange

/**
 * A flow laid over every fresh build of the canvas: the entry ("POST /orders") as a small block of its own, the hops
 * as labelled FLOW lines, and the references that don't exist (an AI's typo, or code removed since) in one grey block.
 */
data class FlowOverlay(val title: String, val entryId: String, val hops: List<ResolvedHop>, val gone: List<String>) {
    fun applyTo(model: BlockModel, filePath: String): BlockModel {
        val present = model.blocks.map { it.id }.toSet()
        if (entryId !in present) return model
        val blocks = model.blocks.toMutableList()
        val links = mutableListOf<Link>()
        blocks += Block(ENTRY_ID, BlockKind.MORE, title, filePath, SourceRange(0, 0), collapsed = true, summary = listOf(ENTRY_NOTE))
        val drawn = hops.filter { it.fromId in present && it.toId in present }
        links += Link(LinkKind.FLOW, ENTRY_ID, firstStep(drawn), label = "routes", claimed = true)
        drawn.forEach { hop -> links += Link(LinkKind.FLOW, hop.fromId, hop.toId, label = hop.kind, claimed = !hop.proven) }
        if (gone.isNotEmpty()) {
            blocks += Block(GONE_ID, BlockKind.MORE, "not found in the code: ${gone.joinToString(", ")}", filePath, SourceRange(0, 0), collapsed = true)
            links += Link(LinkKind.FLOW, ENTRY_ID, GONE_ID, claimed = true)
        }
        // A hop says more than the plain call or "implemented by" line between the same two blocks: show only the hop.
        val hopPairs = links.map { it.from to it.to }.toSet()
        // The class-to-method line of a method on the flow only crosses the flow; the flow already places it.
        val onFlow = links.flatMap { listOf(it.from, it.to) }.toSet()
        // An injected class whose method is on the flow would show up twice, once folded up as a dependency.
        // `callee:<class>::<method>`, or for a Python method `callee:<module>::<Class>.<method>` (the class `<module>.<Class>`).
        val shownClasses = onFlow.filter { it.startsWith("callee:") }.flatMap {
            val owner = it.removePrefix("callee:").substringBefore("::")
            val key = it.substringAfter("::", "")
            if ('.' in key) listOf(owner, "$owner.${key.substringBefore('.').substringBefore('#')}") else listOf(owner)
        }.toSet()
        val doubled = blocks.filter { it.kind == BlockKind.DEPENDENCY && it.id.removePrefix("dependency:") in shownClasses }.map { it.id }.toSet()
        val kept = model.links.filterNot {
            (it.kind == LinkKind.CALLS_INTO || it.kind == LinkKind.IMPLEMENTED_BY) && (it.from to it.to) in hopPairs ||
                it.kind == LinkKind.OWNS && it.to in onFlow ||
                it.from in doubled || it.to in doubled
        }
        return BlockModel(blocks.filterNot { it.id in doubled }, kept + links.distinct())
    }

    /** Where the route lands: the first of the middleware that run before the entry, or the entry itself. */
    private fun firstStep(drawn: List<ResolvedHop>): String {
        var step = entryId
        val seen = mutableSetOf(step)
        while (true) {
            val before = drawn.firstOrNull { it.kind == "middleware" && it.toId == step && it.fromId !in seen } ?: return step
            step = before.fromId
            seen += step
        }
    }

    companion object {
        const val ENTRY_ID = "flow:entry"
        const val GONE_ID = "flow:gone"
        const val ENTRY_NOTE = "route, from the AI (not checked)"
    }
}
