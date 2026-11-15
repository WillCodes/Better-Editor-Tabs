package dev.willcodes.bettereditortabs.tabgroups.tabbar.ide

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionWrapperUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.OverridingAction
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.ui.tabs.impl.MorePopupAware
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitBar
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader

/**
 * Wraps the built-in `TabList` action, the "more" button of a tab strip.
 *
 * The built-in `com.intellij.ide.actions.TabListAction` is internal and final; like [ReopenClosedTabAction] this class is registered
 * with `overrides="true"` under the same id and implements the public marker [OverridingAction], and the replaced action is reached
 * through `ActionManagerImpl.getBaseAction` (loaded from its stub by [PlatformInternals.unstub]). The base's `actionPerformed` asks the
 * data context's `MorePopupAware` (`MorePopupAware.KEY`, `KEY_TOOLWINDOW_TITLE` in a tool window title) to show its popup. When that is
 * the tabs component of an editor split that has groups, [OverflowList] shows the grouped list instead; for everything else (tool
 * windows, other tab components, splits without groups, a project without an attached host) the base action runs, untouched.
 *
 * `update` is the base's, as is the update thread (BGT, so nothing here may touch Swing state).
 * Not marked as a remote-development frontend action like the base: remote development is out of scope and the marker is experimental
 * API. Uses: `ActionManagerImpl.getBaseAction` (documented on `OverridingAction`).
 */
class TabListOverride : AnAction(), OverridingAction, DumbAware {

    @Volatile
    private var resolvedBase: AnAction? = null

    private fun base(): AnAction? {
        resolvedBase?.let { return it }
        val registered = (ActionManager.getInstance() as? ActionManagerImpl)?.getBaseAction(this) ?: return null
        return PlatformInternals.unstub(registered)?.also { resolvedBase = it }
    }

    /** The base action updates on a background thread; so does this one (nothing but the base's update runs there). */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val base = base()
        if (base == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        if (e.presentation.text.isNullOrEmpty()) e.presentation.copyFrom(base.templatePresentation)
        // ActionWrapperUtil: calling AnAction.update directly is an override-only API violation.
        ActionWrapperUtil.update(e, this, base)
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (showGroupedList(e)) return
        base()?.let { ActionWrapperUtil.actionPerformed(e, this, it) }
    }

    /** Shows the grouped overflow list if the event belongs to the strip of an editor split with groups; false = not ours. */
    private fun showGroupedList(e: AnActionEvent): Boolean {
        val project = e.project ?: return false
        val aware = if (e.place == ActionPlaces.TOOLWINDOW_TITLE) e.getData(MorePopupAware.KEY_TOOLWINDOW_TITLE) else e.getData(MorePopupAware.KEY)
        val tabs = aware as? JBTabsImpl ?: return false
        val bar = splitBarOf(project, tabs) ?: return false
        if (bar.groups.isEmpty()) return false
        OverflowList.show(project, tabs, bar)
        return true
    }

    private fun splitBarOf(project: Project, tabs: JBTabsImpl): SplitBar? {
        val tabBar = project.serviceIfCreated<TabBar>() ?: return null
        if (tabBar.host == null) return null
        val window = FileEditorManagerEx.getInstanceEx(project).windows.firstOrNull { !it.isDisposed && StripReader.tabsOf(it) === tabs } ?: return null
        return tabBar.splitBarOf(window)
    }
}
