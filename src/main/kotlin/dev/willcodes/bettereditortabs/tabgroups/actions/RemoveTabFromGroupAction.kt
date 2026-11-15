package dev.willcodes.bettereditortabs.tabgroups.actions

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService

/**
 * CTX-3: Remove Tab from Group; the tab moves to just after its group. In the tab context menu it is visible only for grouped tabs; in
 * other places (Window menu, Find Action: §13) it is always visible and disabled for ungrouped tabs. Disabled for pinned tabs (CTX-4).
 */
class RemoveTabFromGroupAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val context = TabContext.of(e)
        val service = e.project?.getServiceIfCreated(TabGroupsService::class.java)
        val grouped = context != null && service?.groupOf(context.split, context.file) != null
        e.presentation.isVisible = grouped || e.place != ActionPlaces.EDITOR_TAB_POPUP
        e.presentation.isEnabled = context != null && grouped && !context.pinned
    }

    override fun actionPerformed(e: AnActionEvent) {
        val context = TabContext.of(e) ?: return
        if (context.pinned) return
        context.project.service<TabGroupsService>().removeFromGroup(context.split, context.file)
    }
}
