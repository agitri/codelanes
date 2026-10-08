package dev.codelanes.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.codelanes.layout.Point

/** Per-user dragged block positions, stored in the workspace file (not shared via git). */
@Service(Service.Level.PROJECT)
@State(name = "CodeLanesPins", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PinStore : SimplePersistentStateComponent<PinStore.PinState>(PinState()) {
    class PinState : BaseState() {
        /** "filePath|blockId" → "x,y" */
        var pins by map<String, String>()
    }

    fun pins(filePath: String): Map<String, Point> =
        state.pins.entries
            .filter { it.key.startsWith("$filePath|") }
            .mapNotNull { (key, value) ->
                // Hand-edited or corrupt workspace values are skipped, never fatal.
                val parts = value.split(',').mapNotNull { it.trim().toIntOrNull() }
                if (parts.size == 2) key.substringAfter('|') to Point(parts[0], parts[1]) else null
            }
            .toMap()

    fun pin(filePath: String, blockId: String, at: Point) {
        state.pins["$filePath|$blockId"] = "${at.x},${at.y}"
        state.intIncrementModificationCount()
    }

    fun clear(filePath: String) {
        if (state.pins.keys.removeIf { it.startsWith("$filePath|") }) state.intIncrementModificationCount()
    }

    fun prune(filePath: String, liveIds: Set<String>) {
        val removed = state.pins.keys.removeIf { it.startsWith("$filePath|") && it.substringAfter('|') !in liveIds }
        if (removed) state.intIncrementModificationCount()
    }

    companion object {
        fun getInstance(project: Project): PinStore = project.service()
    }
}
