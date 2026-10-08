package com.readcodelikeahuman.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.readcodelikeahuman.layout.Point

/** Per-user dragged block positions, stored in the workspace file (not shared via git). */
@Service(Service.Level.PROJECT)
@State(name = "ReadCodeBlockPins", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PinStore : SimplePersistentStateComponent<PinStore.PinState>(PinState()) {
    class PinState : BaseState() {
        /** "filePath|blockId" → "x,y" */
        var pins by map<String, String>()
    }

    fun pins(filePath: String): Map<String, Point> =
        state.pins.entries
            .filter { it.key.startsWith("$filePath|") }
            .associate { (key, value) ->
                val (x, y) = value.split(',').map(String::toInt)
                key.substringAfter('|') to Point(x, y)
            }

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
