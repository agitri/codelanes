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
        var verticalLayout by property(false)
    }

    companion object {
        val instance: BlocksSettings get() = service()
    }
}
