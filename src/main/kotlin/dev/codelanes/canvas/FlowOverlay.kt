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
 * as labelled FLOW lines, and the references that no longer exist in one grey block.
 */
data class FlowOverlay(val title: String, val entryId: String, val hops: List<ResolvedHop>, val gone: List<String>) {
    fun applyTo(model: BlockModel, filePath: String): BlockModel {
        val present = model.blocks.map { it.id }.toSet()
        if (entryId !in present) return model
        val blocks = model.blocks.toMutableList()
        val links = mutableListOf<Link>()
        blocks += Block(ENTRY_ID, BlockKind.MORE, title, filePath, SourceRange(0, 0), collapsed = true)
        links += Link(LinkKind.FLOW, ENTRY_ID, entryId, label = "routes", claimed = true)
        hops.filter { it.fromId in present && it.toId in present }.forEach { hop ->
            links += Link(LinkKind.FLOW, hop.fromId, hop.toId, label = hop.kind, claimed = !hop.proven)
        }
        if (gone.isNotEmpty()) {
            blocks += Block(GONE_ID, BlockKind.MORE, "no longer in the code: ${gone.joinToString(", ")}", filePath, SourceRange(0, 0), collapsed = true)
            links += Link(LinkKind.FLOW, ENTRY_ID, GONE_ID, claimed = true)
        }
        // A hop says more than the plain call line between the same two blocks: show only the hop.
        val hopPairs = links.map { it.from to it.to }.toSet()
        val kept = model.links.filterNot { it.kind == LinkKind.CALLS_INTO && (it.from to it.to) in hopPairs }
        return BlockModel(blocks, kept + links.distinct())
    }

    companion object {
        const val ENTRY_ID = "flow:entry"
        const val GONE_ID = "flow:gone"
    }
}
