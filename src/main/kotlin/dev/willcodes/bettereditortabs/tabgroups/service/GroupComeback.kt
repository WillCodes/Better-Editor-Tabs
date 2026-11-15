package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.model.groups.AutoGroupRule
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupRestore
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupStash
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.groups.RestorePlace
import dev.willcodes.bettereditortabs.tabgroups.model.groups.StashEntry
import dev.willcodes.bettereditortabs.tabgroups.tabbar.ClosedMark
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import java.util.UUID

/** The name to show in menus and dialogs: [name], or "Unnamed group (Blue)" (CTX-2). */
internal fun groupDisplayName(name: String, color: GroupColor): String =
    name.ifEmpty { BetterEditorTabsBundle.message("group.unnamed", BetterEditorTabsBundle.message(color.messageKey)) }

/**
 * Groups that come back after they were closed: the stash (LIFE-2 Stash Group, LIFE-4 Restore Tab Group, PERS-4, SET-9) and the
 * records of deleted groups for Reopen Closed Tab (LIFE-5). Both bring a group back the same way ([bringBack]). A collaborator of
 * [TabGroupsService]; EDT only, except [hasClosedGroups].
 *
 * **Stash.** A list of snapshots ([GroupStash]) kept apart from [GroupsState]: a stashed group is not a group of the project. Stash
 * Group takes a snapshot of the instance (name, colour, member files in order), removes the instance, closes the tabs without Reopen
 * Closed Tab entries and saves. Restoring opens the files that still exist ([TabGroupsUi.warnMissingFiles] names the others) and
 * recreates the group at the end of the strip (after the split's instance of the same group if it has one, so that stays contiguous).
 *
 * **Closed groups (LIFE-5).** A Delete Group or a Stash Group that closes tabs records the group (snapshot, the file before it, its index) in a stack
 * per split (session only, [MAX_RECORDS] deep) and closes the tabs without history entries, keeping a [ClosedMark] of the split's
 * Reopen Closed Tab history. Reopen Closed Tab brings the top record back if it is still the most recent close of the split: the
 * history top is still the entry that was on top at the delete ([TabBar.isMostRecentClose]); otherwise the platform's reopen runs
 * first, so "delete group, close tab T, reopen, reopen" brings T back, then the group. If the history cannot be read the record
 * counts as the most recent one (fail soft). A stash's record names its stash entry: reopening it restores the group from there and
 * removes the entry; once the entry has been restored or removed through the stash, the record is stale and skipped (user request
 * 2026-11-14: Reopen Closed Tab also undoes a Stash Group).
 *
 * **Rules and empty groups (RULE-1, RULE-6).** A stash entry and a record carry the group's auto-group rule and bring it back with the
 * group. A group with an enabled rule can be empty (its instance stays, MEM-5): Stash Group and Delete Group then still leave an entry
 * or a record, with no files. Restoring such an entry, or reopening such a record, makes an empty instance again (a restore whose
 * files are all missing, with an enabled rule, does the same); the tab bar decides where its pill stands. An empty group's record
 * counts like any other: it is the most recent close of its split unless something was closed after it ([TabBar.closeTabsQuietly]
 * with no files only takes the mark), so Reopen Closed Tab right after deleting an empty group brings the group back.
 *
 * Membership and order when a group comes back are [GroupRestore]'s rules; opening and ordering the tabs is [TabBar.openInOrder].
 */
internal class GroupComeback(
    private val project: Project,
    private val state: GroupsState<SplitHandle, VirtualFile>,
    private val stash: GroupStash,
    private val saved: SavedGroups,
    /** Applies the state to a split's strip and updates the saved snapshot. */
    private val refresh: (SplitHandle) -> Unit,
    /** Puts a split's instances in strip order after an instance was added. */
    private val arrange: (SplitHandle) -> Unit,
    /** Attaches the service to the tab bar (the tab bar needs a host to open and order tabs). */
    private val attach: () -> Unit,
) {
    private val tabBar get() = project.getService(TabBar::class.java)

    /**
     * A group deleted or stashed with its tabs: [anchor] was the tab before it, [index] the place of its first tab in the split's
     * logical order; [stashEntry] is the stash entry of a Stash Group (null for a delete).
     */
    private class ClosedGroup(
        val groupId: String,
        val name: String,
        val color: GroupColor,
        val files: List<VirtualFile>,
        val anchor: VirtualFile?,
        val index: Int,
        val mark: ClosedMark,
        val stashEntry: String? = null,
        val rule: AutoGroupRule? = null,
    )

    private val closed = HashMap<SplitHandle, ArrayList<ClosedGroup>>()

    /** True while a closed group is recorded in any split ([TabBarHost.canReopenClosedGroup]; read from action updates, any thread). */
    @Volatile
    var hasClosedGroups = false
        private set

    // ---- stash -------------------------------------------------------------------------------------------------------------

    /** LIFE-2: see [TabGroupsService.stashGroup]. */
    fun stashGroup(split: SplitHandle, groupId: String) {
        val instance = state.instance(split, groupId) ?: return
        val group = state.group(groupId) ?: return
        val members = instance.members.filter { it.isValid }
        val entry = StashEntry(
            UUID.randomUUID().toString(), groupId, group.name, group.color, members.map { it.url }, System.currentTimeMillis(), group.rule,
        )
        stash.add(entry)
        closeAndRecord(split, groupId, entry.id)
        saved.capture()
        TabGroupStashListener.publish(project)
    }

    fun stashedGroups(): List<StashedGroup> = stash.entries.map { e ->
        val live = state.group(e.groupId)
        val color = live?.color ?: e.color
        StashedGroup(e.id, groupDisplayName(live?.name ?: e.name, color), color, e.files.size, e.stashedAt)
    }

    /** LIFE-4: see [TabGroupsService.restoreStashed]. */
    fun restoreStashed(split: SplitHandle?, entryId: String): Boolean {
        val entry = stash.get(entryId) ?: return false
        val manager = VirtualFileManager.getInstance()
        val files = ArrayList<VirtualFile>()
        val missing = ArrayList<String>()
        for (url in entry.files) {
            val file = manager.findFileByUrl(url)?.takeIf { it.isValid && !it.isDirectory }
            if (file != null) files += file else missing += url.substringAfterLast('/')
        }
        // an identity that lives on in another split keeps its live name and colour (GroupsState.acceptInstance)
        val live = state.group(entry.groupId)
        val shownName = groupDisplayName(live?.name ?: entry.name, live?.color ?: entry.color)
        val restored = when {
            files.isNotEmpty() -> bringBack(split, entry.groupId, entry.name, entry.color, files, RestorePlace.end(), entry.rule)
            entry.rule?.enabled == true -> bringBackEmpty(split, entry.groupId, entry.name, entry.color, entry.rule)
            else -> 0
        }
        if (restored == null) return true // nothing could be opened (no host or window): the entry stays, nothing is lost
        stash.remove(entryId)
        saved.capture()
        TabGroupStashListener.publish(project)
        if (missing.isNotEmpty()) TabGroupsUi.getInstance(project).warnMissingFiles(shownName, missing, restored)
        return true
    }

    fun removeStashed(entryId: String) {
        if (!stash.remove(entryId)) return
        saved.capture()
        TabGroupStashListener.publish(project)
    }

    // ---- closed groups (LIFE-5) ---------------------------------------------------------------------------------------------

    /**
     * LIFE-3, LIFE-5: Delete Group for [split]'s instance of [groupId]: removes the instance, then closes its open tabs without
     * Reopen Closed Tab entries and records the group for Reopen Closed Tab. A group without open tabs leaves no record, unless it is an empty group with an enabled rule (RULE-6).
     */
    fun deleteGroup(split: SplitHandle, groupId: String) = closeAndRecord(split, groupId, stashEntry = null)

    /**
     * Removes [split]'s instance of [groupId], closes its open tabs without Reopen Closed Tab entries and records it for Reopen Closed
     * Tab (LIFE-5; [stashEntry]: the entry a Stash Group just made). A group without open tabs leaves no record, unless it has an
     * enabled rule (RULE-6: an empty group that is still there): then the record has no files.
     */
    private fun closeAndRecord(split: SplitHandle, groupId: String, stashEntry: String?) {
        val instance = state.instance(split, groupId) ?: return
        val group = state.group(groupId)
        val tabs = tabBar.openTabs(split, instance.members)
        val order = tabBar.logicalOrder(split)
        val first = tabs.map { order.indexOf(it) }.filter { it >= 0 }.minOrNull()
        // the instance goes first and the split is refreshed: no tab is hidden while it closes, and MEM-1 is not reported for it
        state.removeInstance(split, groupId)
        refresh(split)
        if (group == null || (tabs.isEmpty() && group.rule?.enabled != true)) return
        // an empty group with an enabled rule is recorded too, with no files (the mark is then just the history's top now)
        val mark = tabBar.closeTabsQuietly(split, tabs)
        val record = ClosedGroup(groupId, group.name, group.color, tabs, first?.let { order.getOrNull(it - 1) }, first ?: order.size, mark, stashEntry, group.rule)
        val stack = closed.getOrPut(split) { ArrayList() }
        stack += record
        if (stack.size > MAX_RECORDS) stack.removeAt(0)
        hasClosedGroups = true
    }

    /** The split is gone: so are its records. */
    fun splitDisposed(split: SplitHandle) {
        if (closed.remove(split) != null) hasClosedGroups = closed.isNotEmpty()
    }

    /** LIFE-5: see [TabBarHost.reopenClosedGroup]. */
    fun reopenClosedGroup(e: AnActionEvent): Boolean {
        val split = tabBar.reopenSplit(e) ?: return false
        val stack = closed[split] ?: return false
        // a stash's record whose entry was restored or removed through the stash meanwhile is stale
        while (stack.lastOrNull()?.stashEntry?.let { stash.get(it) == null } == true) stack.removeAt(stack.lastIndex)
        val record = stack.lastOrNull()
        if (record == null || !tabBar.isMostRecentClose(record.mark)) {
            if (stack.isEmpty()) closed.remove(split)
            hasClosedGroups = closed.isNotEmpty()
            return false
        }
        stack.removeAt(stack.lastIndex)
        if (stack.isEmpty()) closed.remove(split)
        hasClosedGroups = closed.isNotEmpty()
        val files = record.files.filter { it.isValid }
        val missing = record.files.filter { !it.isValid }.map { it.name }
        val place = if (record.anchor != null) RestorePlace.after(record.anchor, record.index) else RestorePlace.index(record.index)
        val live = state.group(record.groupId)
        val shownName = groupDisplayName(live?.name ?: record.name, live?.color ?: record.color)
        val brought = when {
            files.isNotEmpty() -> bringBack(split, record.groupId, record.name, record.color, files, place, record.rule)
            record.rule?.enabled == true -> bringBackEmpty(split, record.groupId, record.name, record.color, record.rule)
            else -> 0
        }
        val restored = brought ?: 0
        // the stash entry goes with it, unless nothing could be opened at all (then it stays in the stash, nothing is lost)
        if (record.stashEntry != null && brought != null && stash.remove(record.stashEntry)) {
            saved.capture()
            TabGroupStashListener.publish(project)
        }
        if (missing.isNotEmpty()) TabGroupsUi.getInstance(project).warnMissingFiles(shownName, missing, restored)
        return true
    }

    // ---- bringing a group back -----------------------------------------------------------------------------------------------

    /**
     * Brings the group [groupId] back into [split] (the split the tabs open in is made if there is none, null) with [files] (the ones
     * that still exist, in group order) at [place], following [GroupRestore]: files that are open there ungrouped join and move, files
     * of other groups and pinned ones stay out, a split that has an instance of the group gets the files behind it. The group is
     * re-linked if its identity still exists (it then keeps its name and colour), else re-created with [name] and [color]; the
     * instance is expanded; the first tab that came back is selected and focused. Returns the number of tabs that joined the group, or null if the files could not be opened at all.
     */
    private fun bringBack(
        split: SplitHandle?,
        groupId: String,
        name: String,
        color: GroupColor,
        files: List<VirtualFile>,
        place: RestorePlace<VirtualFile>,
        rule: AutoGroupRule?,
    ): Int? {
        attach()
        val order = if (split != null) tabBar.logicalOrder(split) else emptyList()
        val groups = if (split != null) state.instancesIn(split).map { it.groupId to it.members.toList() } else emptyList()
        // every pinned tab of the split (they stay first), not only the group's own files
        val pinned = if (split != null) (order + files).filterTo(HashSet()) { tabBar.isPinned(split, it) } else emptySet()
        val plan = GroupRestore.plan(files, order, groups, pinned, groupId, place)
        val toOpen = if (split != null) plan.joining.filter { tabBar.openTabs(split, listOf(it)).isEmpty() } else plan.joining
        val opened = tabBar.openInOrder(split, toOpen, plan.order)
        if (opened == null) {
            LOG.warn("could not open the files of group $groupId")
            return null
        }
        val target = opened.split
        val joined = plan.joining.filter { tabBar.openTabs(target, listOf(it)).isNotEmpty() }
        val existing = state.instance(target, groupId)
        if (joined.isNotEmpty()) {
            state.acceptInstance(target, groupId, name, color, joined, collapsed = false, rule = rule)
            if (existing?.collapsed == true) state.setCollapsed(target, groupId, false)
            // INV-4: a preview tab cannot be a member (MEM-7)
            for (file in joined) if (tabBar.isPreview(target, file)) tabBar.makePermanent(target, file)
        }
        refresh(target)
        arrange(target)
        joined.firstOrNull()?.let { tabBar.selectTab(target, it, true) }
        LOG.info("group $groupId brought back: ${joined.size} of ${files.size} tabs")
        return joined.size
    }

    /**
     * An empty group with an enabled [rule] comes back (RULE-6): an empty, expanded instance in [split], re-linked if the identity
     * lives on. Nothing is opened or moved; the tab bar puts its pill where it puts a group it never showed. Returns 0 (no tab came
     * back), or null if there is no split to put it in (the entry or record then stays).
     */
    private fun bringBackEmpty(split: SplitHandle?, groupId: String, name: String, color: GroupColor, rule: AutoGroupRule?): Int? {
        if (split == null || split !in tabBar.splits()) return null
        attach()
        state.acceptInstance(split, groupId, name, color, emptyList(), collapsed = false, rule = rule)
        refresh(split)
        arrange(split)
        return 0
    }

    private companion object {
        val LOG = logger<GroupComeback>()
        const val MAX_RECORDS = 20
    }
}
