package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredGroups
import dev.willcodes.bettereditortabs.tabgroups.model.persist.rewriteUrls
import dev.willcodes.bettereditortabs.tabgroups.model.persist.withPending
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.SplitIdentity

/**
 * What [TabGroupsService.getState] hands to the platform (PERS-1), kept apart from the live state because of threads. The platform
 * reads the state on a background thread while saving (`ComponentStoreImpl.getStateForComponent`: a read action, not the EDT), but
 * the live [GroupsState] and the tab bar are EDT only. So [bean] is an immutable snapshot, replaced as a whole and never mutated once
 * published, rebuilt on the EDT ([capture]) after every change of the groups.
 *
 * Lifecycle:
 * - until the startup restore has run ([restored]) the bean is the loaded state itself, unchanged, so an early save loses nothing,
 *   and file renames re-address it ([filesMoved]);
 * - after it, every [capture] reflects the live state, plus the stored splits that are still [pending] (PERS-3: no live split has
 *   claimed them yet, a detached window may still be restoring), so a save during that time loses nothing;
 * - [freeze] (project closing): the platform then closes every tab, which would empty the groups (MEM-1), so later captures are ignored.
 */
internal class SavedGroups(private val project: Project, private val state: GroupsState<SplitHandle, VirtualFile>) {
    @Volatile
    var bean: GroupsStateBean = GroupsStateBean()
        private set

    /** True once the startup restore has run; from then on the bean follows the live state. */
    var restored = false
        private set

    private var frozen = false
    private var captureQueued = false

    /** Stored splits (and their group identities) that no live split has claimed yet; empty once restored for good or expired. */
    var pending: StoredGroups = StoredGroups.EMPTY
        private set

    /** Sets what stays pending after a restore pass (or an empty state to end the waiting time) and captures. */
    fun setPending(value: StoredGroups) {
        pending = value
        capture()
    }

    /** The stored state as loaded by the platform; a later restore applies it. */
    fun loaded(loaded: GroupsStateBean) {
        bean = loaded
        restored = false
        frozen = false
        pending = StoredGroups.EMPTY
    }

    /** The loaded state in model form, for the restore planner. */
    fun stored(): StoredGroups = GroupsPersistence.toStored(bean)

    /** The startup restore has run: from now on [capture] replaces the loaded state. */
    fun markRestored() {
        restored = true
    }

    /** Rebuilds [bean] from the live state; a no-op before the restore and while frozen. EDT only. */
    fun capture() {
        if (!restored || frozen || project.isDisposed) return
        try {
            val tabBar = project.getService(TabBar::class.java)
            val live = GroupsPersistence.capture(state, SplitIdentity.allSplits(project), tabBar::logicalOrder)
            bean = GroupsPersistence.toBean(live.withPending(pending))
        }
        catch (e: Exception) {
            LOG.warn("Could not capture the tab groups for saving; keeping the last snapshot", e)
        }
    }

    /** [capture] once the current EDT event is done (drag results: the tab bar applies them after the host callback returns). */
    fun captureLater() {
        if (captureQueued) return
        captureQueued = true
        ApplicationManager.getApplication().invokeLater({
            captureQueued = false
            capture()
        }, project.disposed)
    }

    /** The project is about to be saved and closed: a last [capture], then no more (see the class comment). */
    fun freeze() {
        capture()
        frozen = true
    }

    /** MEM-4: files or directories moved from the first URL to the second; the state not restored yet is re-addressed. EDT only. */
    fun filesMoved(moves: List<Pair<String, String>>) {
        if (!restored) {
            var stored = stored()
            for ((oldUrl, newUrl) in moves) stored = stored.rewriteUrls(oldUrl, newUrl)
            bean = GroupsPersistence.toBean(stored)
        }
        else {
            for ((oldUrl, newUrl) in moves) pending = pending.rewriteUrls(oldUrl, newUrl)
        }
        capture()
    }

    private companion object {
        val LOG = logger<SavedGroups>()
    }
}
