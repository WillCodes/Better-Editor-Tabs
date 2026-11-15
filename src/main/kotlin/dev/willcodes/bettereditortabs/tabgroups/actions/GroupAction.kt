package dev.willcodes.bettereditortabs.tabgroups.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar

/**
 * What a §13 action works on, read from the event: the [split] it ran in, the [context] tab (null when no tab is usable, e.g. every tab
 * is hidden) and the [service]. [groupId] is the "current group" of §13: the group of the context tab (null if it is ungrouped).
 */
internal class GroupActionScope(val project: Project, val service: TabGroupsService, val split: SplitHandle, val context: TabContext?) {
    val groupId: String? get() = context?.let { service.groupOf(split, it.file)?.id }

    companion object {
        /** Null without a project, an editor split, or a group service (it is created with the first group: without it there is nothing to do). */
        fun of(e: AnActionEvent): GroupActionScope? {
            val project = e.project ?: return null
            val service = project.getServiceIfCreated(TabGroupsService::class.java) ?: return null
            val context = TabContext.of(e)
            val split = context?.split ?: project.service<TabBar>().splitFor(e) ?: return null
            return GroupActionScope(project, service, split, context)
        }
    }
}

/**
 * Base of the §13 actions: no default shortcut (plugin.xml), `DumbAware`, updated on the EDT (the group state lives there), enabled by
 * [isEnabled] (cheap, no side effects), which is checked again in [actionPerformed].
 */
abstract class GroupAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    internal abstract fun isEnabled(scope: GroupActionScope): Boolean

    internal abstract fun perform(scope: GroupActionScope, e: AnActionEvent)

    override fun update(e: AnActionEvent) {
        val scope = GroupActionScope.of(e)
        e.presentation.isEnabled = scope != null && isEnabled(scope)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val scope = GroupActionScope.of(e) ?: return
        if (isEnabled(scope)) perform(scope, e)
    }
}
