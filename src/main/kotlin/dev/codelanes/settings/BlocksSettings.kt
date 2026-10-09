package dev.codelanes.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "CodeLanesSettings", storages = [Storage("codelanes.xml")])
class BlocksSettings : SimplePersistentStateComponent<BlocksSettings.SettingsState>(SettingsState()) {
    class SettingsState : BaseState() {
        var openAsBlocksByDefault by property(true)
        /** LANES (default), COLUMN or TREE; see [dev.codelanes.layout.LayoutMode]. */
        var layoutMode by string("LANES")
    }

    val mode: dev.codelanes.layout.LayoutMode
        get() = runCatching { dev.codelanes.layout.LayoutMode.valueOf(state.layoutMode ?: "LANES") }.getOrDefault(dev.codelanes.layout.LayoutMode.LANES)

    private val modeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Calls [listener] whenever the layout mode changes, until [parent] is disposed. */
    fun addModeListener(parent: com.intellij.openapi.Disposable, listener: () -> Unit) {
        modeListeners += listener
        com.intellij.openapi.util.Disposer.register(parent) { modeListeners -= listener }
    }

    /** Switches every open canvas to [mode]. */
    fun setMode(mode: dev.codelanes.layout.LayoutMode) {
        state.layoutMode = mode.name
        modeListeners.forEach { it() }
    }

    companion object {
        val instance: BlocksSettings get() = service()
    }
}
