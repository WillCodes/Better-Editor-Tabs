package dev.willcodes.bettereditortabs.tabgroups.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService
import dev.willcodes.bettereditortabs.tabgroups.ui.popup.GroupPopup

/**
 * CTX-1: Add Tab to New Group. Creates a group holding the tab, then opens the group popup with the name field focused. Disabled for
 * pinned tabs (CTX-4) and when there is no current tab.
 */
open class AddTabToNewGroupAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val context = TabContext.of(e)
        e.presentation.isEnabled = context != null && !context.pinned
    }

    override fun actionPerformed(e: AnActionEvent) {
        val context = TabContext.of(e) ?: return
        if (context.pinned) return
        createAndName(context, e)
    }

    internal fun createAndName(context: TabContext, e: AnActionEvent) {
        val group = context.project.service<TabGroupsService>().createGroup(context.split, context.file) ?: return
        GroupPopup.showForGroup(context.project, context.split, group.id, e.dataContext)
    }
}
