package dev.codelanes.ide

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareToggleAction
import dev.codelanes.layout.LayoutMode
import dev.codelanes.settings.BlocksSettings

/** A View-menu switch for one experimental layout; switching it off goes back to lanes. */
abstract class LayoutModeAction(private val mode: LayoutMode) : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean = BlocksSettings.instance.mode == mode

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        BlocksSettings.instance.setMode(if (state) mode else LayoutMode.LANES)
    }
}

/** Experiment: every block in one column, top to bottom. */
class VerticalLayoutAction : LayoutModeAction(LayoutMode.COLUMN)

/** Experiment: a top-down tree, each level a row, lines from the bottom of a block into the top of the next. */
class TreeLayoutAction : LayoutModeAction(LayoutMode.TREE)
