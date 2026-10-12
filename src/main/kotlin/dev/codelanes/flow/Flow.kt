package dev.codelanes.flow

/** Where a flow starts: what a person calls it ("POST /orders") and the method it lands in (`Fqn::method`). */
data class FlowEntry(val label: String, val method: String)

/** One step of a flow the code alone can't show, e.g. an event to its listener. [kind] is one of [Flow.HOP_KINDS]. */
data class FlowHop(val from: String, val to: String, val kind: String)

/** A request flow as an AI described it: only references to existing methods, never code or explanations. */
data class Flow(val name: String, val entry: FlowEntry, val hops: List<FlowHop>) {
    companion object {
        val HOP_KINDS = listOf("routes", "middleware", "dispatches", "listens", "binds", "calls")
    }
}

/** A flow and the file it's saved in. */
data class SavedFlow(val flow: Flow, val path: String)

sealed interface FlowRead {
    data class Ok(val flow: Flow) : FlowRead
    data object Broken : FlowRead
    data class UnknownVersion(val version: Int) : FlowRead
}
