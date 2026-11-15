package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.groups.InstanceLayout
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar

/**
 * How the groups follow what happens to their tabs for
 * [TabGroupsService], which delegates to it: a member tab closed or pinned ([tabClosed], [tabPinned]), a drag that re-grouped the
 * split ([groupsChangedByDrag]), files renamed, moved or deleted ([filesMoved], [dropInvalidMembers]). Whether to keep a group that
 * ends up empty is asked by [EmptyGroups]. Shares the service's [state]; [refresh] is the service's own. EDT only.
 */
internal class MemberEvents(
    private val project: Project,
    private val state: GroupsState<SplitHandle, VirtualFile>,
    private val saved: SavedGroups,
    private val empties: EmptyGroups,
    private val refresh: (SplitHandle) -> Unit,
) {
    private val tabBar get() = project.service<TabBar>()

    /** The tab bar refreshes the split itself afterwards. A preview tab dropped into a group becomes permanent. */
    fun groupsChangedByDrag(split: SplitHandle, groups: List<GroupDecoration>) {
        // A group whose only tab was dragged out keeps its (empty) instance for now; the user is asked afterwards
        val (keep, ask) = empties.emptiedByDrag(split, groups)
        state.replaceSplit(split, groups.map { InstanceLayout(it.id, it.members, it.collapsed, it.peek) }, keep)
        for (group in groups) for (file in group.members) if (tabBar.isPreview(split, file)) tabBar.makePermanent(split, file)
        saved.captureLater()
        if (ask.isNotEmpty()) ApplicationManager.getApplication().invokeLater { empties.askAfterDrag(split, ask) }
    }

    /** A member tab was closed. */
    fun tabClosed(split: SplitHandle, file: VirtualFile) {
        // The last tab of a group was closed: the instance stays until the user has answered (after the event)
        val instance = state.groupOf(split, file)
        if (instance != null && instance.members.size == 1) empties.lastTabLeaving(split, instance.groupId, EmptyCase.CLOSED)
        removeAfterEvent(split, file)
    }

    /**
     * The platform moves the pinned tab itself (see [dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBarHost.tabPinned]).
     * Pinning a group's last tab asks like closing it (whatever would remove a group by itself asks first).
     */
    fun tabPinned(split: SplitHandle, file: VirtualFile) {
        val instance = state.groupOf(split, file)
        if (instance != null && instance.members.size == 1) empties.lastTabLeaving(split, instance.groupId, EmptyCase.PINNED)
        removeAfterEvent(split, file)
    }

    private fun removeAfterEvent(split: SplitHandle, file: VirtualFile) {
        state.removeFromGroup(split, file)
        refresh(split)
    }

    /**
     * Files or directories were renamed or moved (old URL to new URL). The live state holds `VirtualFile`s, which keep their
     * identity, and the platform keeps the tab open (`FileEditorManagerImpl.MyVirtualFileListener`), so the groups follow by themselves;
     * only the stored form names files by URL, and so do the directory rules, which are rewritten live. EDT only.
     */
    fun filesMoved(moves: List<Pair<String, String>>) {
        for ((oldUrl, newUrl) in moves) state.rewriteRuleUrls(oldUrl, newUrl) // directory rules of the live groups follow too
        saved.filesMoved(moves)
    }

    /**
     * Files were deleted. The platform has closed their tabs (which removed them from their groups); this makes sure no
     * member is left whose `VirtualFile` is invalid, and that an emptied group goes. EDT only.
     */
    fun dropInvalidMembers() {
        for (split in state.splits()) {
            val invalid = state.instancesIn(split).flatMap { inst -> inst.members.filter { !it.isValid } }
            if (invalid.isEmpty()) continue
            for (file in invalid) state.removeFromGroup(split, file)
            refresh(split)
        }
    }
}
