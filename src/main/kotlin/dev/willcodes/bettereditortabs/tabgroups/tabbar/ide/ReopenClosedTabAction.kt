package dev.willcodes.bettereditortabs.tabgroups.tabbar.ide

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionWrapperUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.OverridingAction
import com.intellij.openapi.actionSystem.impl.ActionManagerImpl
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.DumbAware
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBarHost
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals

/**
 * Wraps the built-in Reopen Closed Tab action (LIFE-5, LIFE-6).
 *
 * The built-in `com.intellij.openapi.fileEditor.impl.ReopenClosedTabAction` is an internal, final class, so it cannot be
 * subclassed. Instead this class is registered with `overrides="true"` under the same id (`ReopenClosedTab`) and implements
 * the public marker [OverridingAction]; the platform then hands back the replaced action through
 * `ActionManagerImpl.getBaseAction`, which is used to run the default behaviour. On plugin unload the platform restores the
 * base action itself (`ActionPluginRegistrar.unloadActionElement`).
 *
 * Uses: `ActionManagerImpl.getBaseAction` (a public method of an implementation class, documented on `OverridingAction`).
 */
class ReopenClosedTabAction : AnAction(), OverridingAction, DumbAware {

    /** The attached host, or null while the plugin is inert (then this action is just the built-in one). */
    private fun activeHost(e: AnActionEvent): TabBarHost? = e.project?.serviceIfCreated<TabBar>()?.host

    /** The built-in action, loaded once: the platform hands it back as an unloaded `ActionStub` (see [PlatformInternals.unstub]). */
    @Volatile
    private var resolvedBase: AnAction? = null

    private fun base(): AnAction? {
        resolvedBase?.let { return it }
        val registered = (ActionManager.getInstance() as? ActionManagerImpl)?.getBaseAction(this) ?: return null
        return PlatformInternals.unstub(registered)?.also { resolvedBase = it }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = base()?.actionUpdateThread ?: ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val base = base()
        if (base == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        // The overriding action has no text of its own; borrow the built-in presentation.
        if (e.presentation.text.isNullOrEmpty()) e.presentation.copyFrom(base.templatePresentation)
        // ActionWrapperUtil: calling AnAction.update/actionPerformed directly is an override-only API violation.
        ActionWrapperUtil.update(e, this, base)
        if (activeHost(e)?.canReopenClosedGroup() == true) e.presentation.isEnabledAndVisible = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val host = activeHost(e)
        // LIFE-5: if the most recent close was a Delete Group, restore the whole group instead of the single tab.
        if (host != null && host.reopenClosedGroup(e)) return
        // Default behaviour. The reopened tab goes through FileEditorManagerImpl.openFileImpl, so fileOpened fires as usual (RULE-2).
        base()?.let { ActionWrapperUtil.actionPerformed(e, this, it) }
    }
}
