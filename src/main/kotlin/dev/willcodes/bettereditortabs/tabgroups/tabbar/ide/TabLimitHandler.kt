package dev.willcodes.bettereditortabs.tabgroups.tabbar.ide

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorAutoClosingHandler
import com.intellij.openapi.fileEditor.impl.EditorComposite
import com.intellij.openapi.project.ProjectManager
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar

/**
 * IDE-3: grouped tabs, collapsed or not, don't count toward the editor tab limit and are never closed by it.
 *
 * Wraps the internal, experimental extension point `com.intellij.editorAutoClosingHandler`
 * ([EditorAutoClosingHandler]). The platform calls [isClosingAllowed] for every tab when it trims a split to
 * `UISettings.editorTabLimit` (`EditorWindow.doTrimSize`): a tab for which it returns false is skipped when choosing
 * what to close and is also subtracted from the tab count (`tabsExcludedFromLimitCount`). The answer is also persisted
 * per tab as `EXCLUDED_FROM_TAB_LIMIT` in the workspace file, so the restore-time trim (`UiBuilder.process`) keeps grouped tabs too.
 *
 * The handler receives only the composite, so the owning split is found by scanning the project's windows for it. It can be called
 * off the EDT, so the group lookup ([TabBar.groupIdOf]) reads an immutable snapshot.
 */
class TabLimitHandler : EditorAutoClosingHandler {
    override fun isClosingAllowed(composite: EditorComposite): Boolean {
        // EditorComposite.fileEditorManager is deprecated (error level) and .project is internal, so find the owner by scanning.
        for (project in ProjectManager.getInstance().openProjects) {
            // Cheap check first: a project without an attached host has no groups, so its windows need no scan.
            val tabBar = project.serviceIfCreated<TabBar>()?.takeIf { it.host != null } ?: continue
            val manager = FileEditorManagerEx.getInstanceExIfCreated(project) ?: continue
            for (window in manager.windows) {
                if (window.getComposite(composite.file) !== composite) continue
                return tabBar.groupIdOf(SplitHandle(window), composite.file) == null
            }
        }
        return true
    }
}
