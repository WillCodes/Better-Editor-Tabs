package dev.willcodes.bettereditortabs.tabgroups.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import dev.willcodes.bettereditortabs.tabgroups.ui.popup.GroupPopup

/**
 * CTX-5: New Tab Group (Find Action; the tab strip's own context menu offers *New Group*). Creates an empty, unnamed group with the next
 * colour at the end of the current split's strip and opens its group popup with the name field focused. Enabled when there is a split.
 */
class NewEmptyGroupAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && project.service<TabBar>().splitFor(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val split = project.service<TabBar>().splitFor(e) ?: return
        createAndName(project, split, e.dataContext)
    }

    companion object {
        /** Creates the empty group in [split] and opens its popup; returns the group's id, null if the split is gone. */
        fun createAndName(project: Project, split: SplitHandle, dataContext: DataContext?): String? {
            val group = project.service<TabGroupsService>().createEmptyGroup(split) ?: return null
            GroupPopup.showForNewGroup(project, split, group.id, dataContext)
            return group.id
        }
    }
}
