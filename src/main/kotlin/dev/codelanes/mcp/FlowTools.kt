package dev.codelanes.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import dev.codelanes.flow.FlowFiles
import dev.codelanes.flow.FlowInput
import dev.codelanes.flow.FlowResolver
import dev.codelanes.flow.FlowService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * CodeLanes tools for AI assistants, through PhpStorm's MCP server (only loaded when that plugin is there, see
 * `codelanes-mcp.xml`). The AI reads the code and names the hops of a request; CodeLanes checks them and draws them.
 * Only plain strings cross the boundary, so nothing depends on the server's serialization library version.
 */
class FlowTools : McpToolset {
    @McpTool(name = "codelanes_show_flow")
    @McpDescription(
        """
        Shows a request flow on the CodeLanes canvas in PhpStorm and saves it for the team, so a person can read the
        real code of the whole flow. Read the code first; CodeLanes checks every reference and draws nothing that
        doesn't exist. Give only the hops the code can't show by itself (framework wiring); CodeLanes follows ordinary
        method calls on its own. Write each hop as  From\Class::method --kind--> To\Class::method  with kind one of:
        routes (a route lands in an action), middleware (a middleware runs before the next step),
        dispatches (code fires an event, job or message; 'to' is the event, written App\Events\OrderPlaced::class),
        listens (a listener or handler picks it up; 'from' is the event as ...::class, 'to' the handler method),
        binds (an interface or abstract method ends up in a bound class), calls (a plain call that matters to the story).
        Use fully qualified class names and existing methods only. Don't explain the code. Use the same name again to
        update a flow (see codelanes_list_flows). The reply lists references that don't exist, so you can fix them.
        """,
    )
    suspend fun codelanes_show_flow(
        @McpDescription("Name of the flow, usually the route, e.g. \"POST /orders\"")
        name: String,
        @McpDescription("Method the request lands in, e.g. \"App\\Http\\Controllers\\OrderController::store\"")
        entry_method: String,
        @McpDescription("The hops in the order they run, each as \"From\\Class::method --kind--> To\\Class::method\"")
        hops: List<String> = emptyList(),
        @McpDescription("Label shown on the entry block; defaults to the name")
        entry_label: String = "",
    ): String {
        val project = projectOf(currentCoroutineContext()) ?: return NO_PROJECT
        val checked = FlowInput.check(name, entry_label, entry_method, emptyList(), hops)
        val flow = checked.flow ?: return checked.error!!
        // Resolving reads a lot of PSI: in the background, after indexing, cancellable. Only showing needs the EDT.
        val resolved = smartReadAction(project) { FlowResolver.resolve(project, flow) }
        return withContext(UI) { FlowService.getInstance(project).show(flow, resolved) }
    }

    @McpTool(name = "codelanes_list_flows")
    @McpDescription("Lists the request flows saved for CodeLanes in this project: name, entry method and file.")
    suspend fun codelanes_list_flows(): String {
        val project = projectOf(currentCoroutineContext()) ?: return NO_PROJECT
        val flows = withContext(UI) { FlowFiles.getInstance(project).list() }
        if (flows.isEmpty()) return "No flows saved yet."
        return flows.joinToString("\n") { "${it.flow.name} — ${it.flow.entry.method} — ${it.flow.hops.size} hops — ${it.path}" }
    }

    private companion object {
        /** The EDT, but never on top of a modal dialog (Settings, a commit): opening files and writing need that. */
        val UI = Dispatchers.EDT + ModalityState.nonModal().asContextElement()

        const val NO_PROJECT = "No PhpStorm project to show the flow in. Open the project in PhpStorm first."

        /** Where the MCP server keeps the calling project moved between versions (2025.2 vs 2026.1), so look it up by name. */
        private val PROJECT_ACCESSORS = listOf("com.intellij.mcpserver.McpCallInfoKt", "com.intellij.mcpserver.ProjectContextElementKt")

        fun projectOf(context: CoroutineContext): Project? {
            val fromServer = PROJECT_ACCESSORS.firstNotNullOfOrNull { name ->
                runCatching {
                    Class.forName(name, true, McpToolset::class.java.classLoader)
                        .getMethod("getProjectOrNull", CoroutineContext::class.java)
                        .invoke(null, context) as Project?
                }.getOrNull()
            }
            if (fromServer != null) return fromServer
            val open = ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }
            return open.singleOrNull() ?: IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
        }
    }
}
