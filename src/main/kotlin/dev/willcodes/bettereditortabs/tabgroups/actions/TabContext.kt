package dev.willcodes.bettereditortabs.tabgroups.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar

/**
 * The tab a grouping action applies to (CTX-1..CTX-4): the right-clicked tab, else the selected tab, of the split the event comes
 * from. [pinned] tabs get the items disabled (CTX-4).
 */
internal class TabContext(val project: Project, val split: SplitHandle, val file: VirtualFile, val pinned: Boolean) {
    companion object {
        /**
         * Null if there is no usable tab: no project or split, or the tab is not showing (every tab hidden by collapsed groups leaves no
         * current editor).
         */
        fun of(e: AnActionEvent): TabContext? {
            val project = e.project ?: return null
            val tabBar = project.service<TabBar>()
            val split = tabBar.splitFor(e) ?: return null
            val file = tabBar.contextFile(split) ?: return null
            if (file !in tabBar.visibleFiles(split)) return null
            return TabContext(project, split, file, tabBar.isPinned(split, file))
        }
    }
}
