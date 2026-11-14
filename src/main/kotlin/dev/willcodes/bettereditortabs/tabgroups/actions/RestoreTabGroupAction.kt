package dev.willcodes.bettereditortabs.tabgroups.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import dev.willcodes.bettereditortabs.tabgroups.ui.popup.RestoreGroupPopup

/** LIFE-4: Restore Tab Group…: a list of the groups closed with Stash Group; picking one brings it back in the split the action ran in. Enabled only with a non-empty stash. */
class RestoreTabGroupAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val service = e.project?.getServiceIfCreated(TabGroupsService::class.java)
        e.presentation.isEnabled = service != null && service.stashedGroups().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        RestoreGroupPopup.show(project, project.service<TabBar>().splitFor(e), e.dataContext)
    }
}
