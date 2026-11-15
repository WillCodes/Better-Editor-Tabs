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
 * How the groups follow what happens to their tabs (MEM-1, MEM-3, MEM-4, MEM-5 amended, MEM-7, DRAG-8 amended) for
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

    /** DRAG-*: the tab bar refreshes the split itself afterwards. MEM-7: a preview tab dropped into a group becomes permanent. */
    fun groupsChangedByDrag(split: SplitHandle, groups: List<GroupDecoration>) {
        // DRAG-8 amended: a group whose only tab was dragged out keeps its (empty) instance for now; the user is asked afterwards
        val (keep, ask) = empties.emptiedByDrag(split, groups)
        state.replaceSplit(split, groups.map { InstanceLayout(it.id, it.members, it.collapsed, it.peek) }, keep)
        for (group in groups) for (file in group.members) if (tabBar.isPreview(split, file)) tabBar.makePermanent(split, file)
        saved.captureLater()
        if (ask.isNotEmpty()) ApplicationManager.getApplication().invokeLater { empties.askAfterDrag(split, ask) }
    }

    /** MEM-1. */
    fun tabClosed(split: SplitHandle, file: VirtualFile) {
        // MEM-5 / RULE-6 amended: the last tab of a group was closed: the instance stays until the user has answered (after the event)
        val instance = state.groupOf(split, file)
        if (instance != null && instance.members.size == 1) empties.lastTabLeaving(split, instance.groupId, EmptyCase.CLOSED)
        removeAfterEvent(split, file)
    }

    /**
     * MEM-3: the platform moves the pinned tab itself (see [dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBarHost.tabPinned]).
     * Pinning a group's last tab asks like closing it (MEM-5 amended: whatever would remove a group by itself asks first).
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
     * MEM-4: files or directories were renamed or moved (old URL to new URL). The live state holds `VirtualFile`s, which keep their
     * identity, and the platform keeps the tab open (`FileEditorManagerImpl.MyVirtualFileListener`), so the groups follow by themselves;
     * only the stored form names files by URL, and so do the directory rules (RULE-1), which are rewritten live. EDT only.
     */
    fun filesMoved(moves: List<Pair<String, String>>) {
        for ((oldUrl, newUrl) in moves) state.rewriteRuleUrls(oldUrl, newUrl) // directory rules of the live groups follow too
        saved.filesMoved(moves)
    }

    /**
     * MEM-4: files were deleted. The platform has closed their tabs (which removed them from their groups, MEM-1); this makes sure no
     * member is left whose `VirtualFile` is invalid, and that an emptied group goes (MEM-5). EDT only.
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
