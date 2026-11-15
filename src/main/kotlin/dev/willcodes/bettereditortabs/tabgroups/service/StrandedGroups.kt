package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle

/**
 * SPLIT-6: the empty groups of a temporary split that the user closes ([dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBarHost.temporarySplitClosing]).
 * A collaborator of [TabGroupsService]; EDT only.
 *
 * Each empty group moves into the split used last that has tabs ([target]: the most recently focused split other than the closing
 * one, with at least one tab and not itself temporary), at the end of its strip (an instance the strip never showed goes to the end);
 * when there is no such split the group's instance is deleted (the group goes with its last instance, MEM-6). One warning
 * ([TabGroupsUi.warnTemporarySplitClosed]) names what was moved and what was deleted; it comes after the event, never inside the
 * platform's close. Works on the state only, never on the closing split's strip: the split is on its way out and may be gone already.
 * Nothing happens while the project closes ([SavedGroups.isFrozen]).
 */
internal class StrandedGroups(
    private val project: Project,
    private val state: GroupsState<SplitHandle, VirtualFile>,
    private val saved: SavedGroups,
    private val refresh: (SplitHandle) -> Unit,
    private val arrange: (SplitHandle) -> Unit,
    /** The split to move the groups of [it] to, or null if there is none (see the class comment). */
    private val target: (SplitHandle) -> SplitHandle?,
) {
    fun closing(split: SplitHandle, groups: List<GroupDecoration>) {
        if (saved.isFrozen || project.isDisposed) return
        // only the instances that are empty: a group with members listed is not stranded (it has tabs somewhere else in the state)
        val ids = groups.map { it.id }.filter { id -> state.instance(split, id)?.members?.isEmpty() == true }
        if (ids.isEmpty()) return
        val to = target(split)
        val moved = ArrayList<String>()
        val deleted = ArrayList<String>()
        for (id in ids) {
            val group = state.group(id) ?: continue
            val instance = state.instance(split, id) ?: continue
            val name = groupDisplayName(group.name, group.color)
            if (to != null) {
                // into the target first, so the identity survives the removal of the source instance (MEM-6)
                state.acceptInstance(to, id, group.name, group.color, emptyList(), instance.collapsed, null, keepEmpty = !group.hasEnabledRule)
                state.removeInstance(split, id)
                moved += name
            }
            else {
                state.removeInstance(split, id)
                deleted += name
            }
        }
        if (to != null && moved.isNotEmpty()) {
            refresh(to)
            arrange(to)
        }
        saved.capture()
        if (moved.isEmpty() && deleted.isEmpty()) return
        ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed && !saved.isFrozen) TabGroupsUi.getInstance(project).warnTemporarySplitClosed(moved, deleted)
        }, project.disposed)
    }
}
