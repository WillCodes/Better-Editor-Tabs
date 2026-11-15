package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import dev.willcodes.bettereditortabs.tabgroups.model.groups.AutoGroupRule
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteTrigger
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupOrder
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupStash
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.groups.TabGroup
import dev.willcodes.bettereditortabs.tabgroups.model.groups.nextColor
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.StripStyle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBarHost
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.SplitIdentity
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.SplitInfo
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JComponent
import java.util.UUID

/**
 * The project's tab groups (§2): the state ([GroupsState]) plus the operations (CTX-1..CTX-3 add and remove tabs, SPLIT-3 rename and
 * recolour, LIFE-1 ungroup, LIFE-3 delete, LIFE-2 close group, LIFE-4 restore from the stash), and the [TabBarHost] the [TabBar] reports to. Talks to the editor tab bars only through the
 * [TabBar] facade, and to popups and dialogs only through [TabGroupsUi] (implemented in `ui/`, which depends on this package, never
 * the other way round). EDT only.
 *
 * Attaching: the service attaches itself ([TabBar.host]) on the first group creation (or when a restart restores groups) and stays
 * attached for the session; detaching when the groups run out would dispose the adapter that is calling it.
 *
 * Persistence (PERS-1, PERS-3): the project's [PersistentStateComponent] in the workspace file. [getState] may run on a background
 * thread, so it returns the immutable snapshot kept by [SavedGroups], which every change refreshes on the EDT (through [refresh]).
 * [restore] applies the loaded state once the restored editor layout has settled.
 *
 * Auto-group rules (RULE-1..RULE-6, POP-2): a tab that becomes permanent and is unpinned and ungrouped (a file opened, Reopen Closed
 * Tab, a preview tab promoted; never a preview tab or a restored tab) goes through SPLIT-2 first and then through the rules
 * ([AutoGroupRules.applyRules]); the popup sets a group's rule with [applyRule], which may offer to move the tabs that match already.
 *
 * Groups that come back (LIFE-2, LIFE-4, LIFE-5): the stash (snapshots of closed groups, apart from [GroupsState], saved with it and
 * usable before the startup restore) and the records of deleted groups that Reopen Closed Tab brings back live in [GroupComeback]; the
 * functions here only delegate.
 */
@Service(Service.Level.PROJECT)
@State(name = "BetterEditorTabsGroups", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TabGroupsService(private val project: Project) : Disposable, TabBarHost, PersistentStateComponent<GroupsStateBean> {
    private val state = GroupsState<SplitHandle, VirtualFile> { UUID.randomUUID().toString() }
    private val tabBar get() = project.service<TabBar>()
    private val settings get() = TabGroupsSettings.getInstance()

    /** The stashed groups (LIFE-2, PERS-4), apart from [state]. */
    private val stash = GroupStash()

    /** What [getState] returns (see [SavedGroups]). */
    private val saved = SavedGroups(project, state, stash)

    /** The stash and the records of deleted groups: groups that come back (LIFE-4, LIFE-5). */
    private val comeback = GroupComeback(project, state, stash, saved, refresh = { refresh(it) }, arrange = { arrange(it) }, attach = ::attach)

    /** The auto-group rules (RULE-*, POP-2). */
    private val rules = AutoGroupRules(project, state, saved, refresh = { refresh(it) }, arrange = { arrange(it) }, isHost = { tabBar.host === this })

    /** Empty groups without a rule: New Group and the "keep it?" question (CTX-5, MEM-5 amended). */
    private val empties = EmptyGroups(project, state, saved, refresh = { refresh(it) }, arrange = { arrange(it) }, isLive = { it in tabBar.splits() })

    /** SPLIT-6: the empty groups of a temporary split the user closes move to the split used last, or are deleted. */
    private val stranded = StrandedGroups(
        project, state, saved, refresh = { refresh(it) }, arrange = { arrange(it) },
        target = { from ->
            tabBar.recentSplits().firstOrNull { it != from && !tabBar.isTemporary(it) && tabBar.logicalOrder(it).isNotEmpty() }
        },
    )

    /** Delete Group (LIFE-3, LIFE-3a..c). */
    private val deletes = GroupDeletes(project, state, comeback, refresh = { refresh(it) })

    /** SPLIT-2 and joining a group's instance (CTX-2, RULE-2, INT-6). */
    private val linked = LinkedJoins(
        project, state, saved, rules, refresh = { refresh(it) }, arrange = { arrange(it) },
        leaveGroup = { split, file -> leaveGroup(split, file) }, isHost = { tabBar.host === this },
    )

    /** The startup restore (PERS-3); the late splits are handed to [lateRestore], declared below. */
    private val groupsRestore: GroupsRestore = GroupsRestore(
        project, state, saved,
        refresh = { split, animate -> refresh(split, animate) }, arrange = { split, animate -> arrange(split, animate) },
        attach = ::attach, startLate = { first, pending -> lateRestore.start(first, pending) },
    )

    /** Tabs closed or pinned, drags, files moved or deleted (MEM-*, DRAG-8). */
    private val memberEvents = MemberEvents(project, state, saved, empties, refresh = { refresh(it) })

    /** Header clicks, hover and the expand / collapse decisions (INT-*, COLL-4). */
    private val headers = HeaderInteractions(project, state, refresh = { refresh(it) }, requestDelete = { s, g, t -> requestDelete(s, g, t) })

    /** The §13 actions on the current group and on all groups (toggle, collapse others, expand all, move, select). */
    private val commands = GroupCommands(project, state, headers, refresh = { refresh(it) }, addToGroup = { s, f, g -> addToGroup(s, f, g) })

    /** PERS-3 for detached windows restored after the first pass (see [restore]). Internal for tests. */
    internal val lateRestore: LateSplitRestore = LateSplitRestore(
        this,
        saved,
        liveSplits = { SplitIdentity.allSplits(project) },
        isLive = { it in tabBar.splits() },
        hasInstances = { state.instancesIn(it).isNotEmpty() },
        apply = { plan, splits ->
            if (plan.splits.isNotEmpty()) attach()
            groupsRestore.applyPlan(plan, splits)
        },
        joinLinked = { split, files -> linked.joinLinkedThenRules(split, files) },
    )

    // ---- queries -----------------------------------------------------------------------------------------------------------

    /** Every group of the project, in creation order (CTX-2 lists them in this order). */
    fun groups(): List<TabGroup> = state.groups.toList()

    fun group(groupId: String): TabGroup? = state.group(groupId)

    /** The group [file] belongs to in [split], if any. */
    fun groupOf(split: SplitHandle, file: VirtualFile): TabGroup? = state.groupOf(split, file)?.let { state.group(it.groupId) }

    /** The theme-aware colour of [group]. */
    fun colorOf(group: TabGroup): JBColor = GroupColors.jbColor(group.color)

    /** The name to show in menus: the group's name, or "Unnamed group (Blue)" (CTX-2). */
    fun displayName(group: TabGroup): String = groupDisplayName(group.name, group.color)

    // ---- operations (each ends with a refresh of the split) -------------------------------------------------------------

    /**
     * CTX-1: a new group containing [file] only, at the tab's position, with the next colour (COL-1). Null (nothing done) if the tab is
     * pinned (INV-3) or not visible in [split]. A preview tab becomes permanent (MEM-7, INV-4). If the tab is in another group of the
     * split it first moves to just after that group, so the old group stays contiguous (INV-2).
     */
    fun createGroup(split: SplitHandle, file: VirtualFile): TabGroup? {
        if (!prepare(split, file)) return null
        leaveGroup(split, file)
        val color = nextColor(state.groups.map { it.color }, settings.fixedNewGroupColor, settings.startColor)
        val group = state.createGroup(split, file, color)
        refresh(split)
        arrange(split)
        return group
    }

    /**
     * CTX-2: [file] becomes the last member of group [groupId] in [split]; the tab moves to just after the group's last tab (INV-2). Without
     * an instance in the split a new one is created at the tab's position. A collapsed target is expanded first (decision: the user sees
     * where the tab went). Picking the tab's own group moves it to the end of that group. Refused like [createGroup]; returns whether
     * anything was done.
     */
    fun addToGroup(split: SplitHandle, file: VirtualFile, groupId: String): Boolean {
        if (state.group(groupId) == null || !prepare(split, file)) return false
        var target = state.instance(split, groupId)
        if (target != null && file in target) {
            // CTX-2 on the tab's own group: it moves to the end of the group (nothing to do if it already is the last tab).
            if (target.members.last() == file) return false
            leaveGroup(split, file)
            state.addToGroup(split, groupId, file)
            refresh(split)
            return true
        }
        linked.joinInstance(split, file, groupId, leave = true)
        return true
    }

    /** CTX-3: [file] leaves its group in [split] and moves to just after the group. */
    fun removeFromGroup(split: SplitHandle, file: VirtualFile) {
        if (state.groupOf(split, file) == null) return
        leaveGroup(split, file)
        state.removeFromGroup(split, file)
        refresh(split)
    }

    /** Renames [groupId] (empty = unnamed) and refreshes every split that shows it (SPLIT-3). */
    fun rename(groupId: String, name: String) {
        val group = state.group(groupId) ?: return
        if (group.name == name) return
        group.name = name
        refreshSplitsOf(groupId)
    }

    /** Changes the colour of [groupId] and refreshes every split that shows it (SPLIT-3). */
    fun recolor(groupId: String, color: GroupColor) {
        val group = state.group(groupId) ?: return
        if (group.color == color) return
        group.color = color
        refreshSplitsOf(groupId)
    }

    /**
     * LIFE-1: removes [split]'s instance of [groupId]. Its tabs stay open where they are, ungrouped; other splits are unaffected. The
     * tabs of a collapsed group were hidden: the refresh brings them back at the header's place ([TabBar.refresh] shows what is no
     * longer collapsed, after the tab the group sat behind).
     */
    fun ungroup(split: SplitHandle, groupId: String) {
        if (state.instance(split, groupId) == null) return
        state.removeInstance(split, groupId)
        refresh(split)
    }

    /**
     * LIFE-3: Delete Group for [split]'s instance of [groupId]. A non-empty group asks first (LIFE-3b) unless the setting of [trigger]
     * is off (SET-4 for [DeleteTrigger.MENU], SET-5 for [DeleteTrigger.MIDDLE_CLICK]); a group without open tabs goes at once (LIFE-3c).
     * "Don't ask again" switches off the setting of [trigger] only.
     *
     * LIFE-3a: if the group also has instances in other splits and this one has open tabs, a Move / Close / Cancel dialog is asked
     * instead, always (it is a choice, not a confirmation: SET-4, SET-5 and "don't ask again" do not apply). Move sends the tabs to the
     * instance in the most recently focused other split; Close deletes this instance as usual; other splits' instances
     * are never touched by a delete. See [GroupDeletes].
     */
    fun requestDelete(split: SplitHandle, groupId: String, trigger: DeleteTrigger) = deletes.requestDelete(split, groupId, trigger)

    // ---- §13 actions (the work is in GroupCommands; "current group" = the group of the context tab) ----------------------

    /** §13 Toggle Collapse of Current Group; for an ungrouped tab the group this action collapsed last in [split] (see [GroupCommands]). */
    fun toggleCurrentGroup(split: SplitHandle, file: VirtualFile?) = commands.toggleCurrent(split, file)
    fun canToggleCurrentGroup(split: SplitHandle, file: VirtualFile?) = commands.toggleTarget(split, file) != null

    /** §13 Collapse Other Groups / Expand All Groups (this split only, SPLIT-4); empty groups are not touched. */
    fun collapseOtherGroups(split: SplitHandle, file: VirtualFile?) = commands.collapseOthers(split, file)
    fun canCollapseOtherGroups(split: SplitHandle, file: VirtualFile?) = commands.canCollapseOthers(split, file)
    fun expandAllGroups(split: SplitHandle) = commands.expandAll(split)
    fun canExpandAllGroups(split: SplitHandle) = commands.canExpandAll(split)

    /** §13 Move Tab to Next / Previous Group: as picking that group in CTX-2 ([addToGroup]). */
    fun moveToAdjacentGroup(split: SplitHandle, file: VirtualFile, next: Boolean) = commands.moveToAdjacent(split, file, next)
    fun canMoveToAdjacentGroup(split: SplitHandle, file: VirtualFile, next: Boolean) = commands.adjacentGroup(split, file, next) != null

    /** §13 Select Next / Previous Tab in Group (cycling). */
    fun selectInGroup(split: SplitHandle, file: VirtualFile, next: Boolean) = commands.selectInGroup(split, file, next)
    fun canSelectInGroup(split: SplitHandle, file: VirtualFile) = commands.canSelectInGroup(split, file)

    // ---- auto-group rules (RULE-1..RULE-6, POP-2; the work is in AutoGroupRules) --------------------------------------

    /**
     * RULE-1, SPLIT-3: the group popup closed (POP-1 commits on close) with [rule] as [groupId]'s rule (null: none). The rule is shared
     * by every instance. Once the group no longer has an enabled rule its empty instances go (MEM-5, RULE-6); with one they stay.
     *
     * POP-2: if [offerMatching] (the popup decides: closed by Enter, Escape or a click outside, not by one of its actions, with Advanced
     * open), [rule] is active (also if it did not change) and SET-8 is on, the open tabs that match it and are ungrouped, unpinned and not preview tabs
     * are counted in every split ([ruleMatches]); if there are any, [TabGroupsUi.askMoveMatching] asks and Yes moves them
     * ([moveRuleMatches]). EDT.
     */
    fun applyRule(groupId: String, rule: AutoGroupRule?, offerMatching: Boolean) = rules.applyRule(groupId, rule, offerMatching)

    /**
     * POP-2: per live split, the open tabs (in that split's logical order) that match [groupId]'s rule and are ungrouped, unpinned and
     * not preview tabs. Empty if the group has no active rule.
     */
    fun ruleMatches(groupId: String): Map<SplitHandle, List<VirtualFile>> = rules.ruleMatches(groupId)

    /**
     * POP-2 "Yes": moves the tabs of [ruleMatches] into [groupId]: in a split with an instance they join at its end (a collapsed
     * instance expands, as CTX-2); in a split without one they become a new instance placed after the split's existing groups (RULE-3).
     * One re-ordering, refresh and arrangement per split.
     */
    fun moveRuleMatches(groupId: String) = rules.moveRuleMatches(groupId)

    // ---- empty groups and the rule preview (CTX-5, MEM-5 amended, POP-4; the work is in EmptyGroups and AutoGroupRules) --------

    /**
     * CTX-5: a new, empty group in [split] (next colour, COL-1; unnamed), its pill at the end of the strip. It stays while its group popup
     * is open (the caller opens it); [groupPopupClosed] then asks whether to keep it if it is still empty and has no enabled rule.
     * Null if [split] is gone. EDT.
     */
    fun createEmptyGroup(split: SplitHandle): TabGroup? {
        if (split !in tabBar.splits()) return null
        attach()
        return empties.create(split)
    }

    /**
     * POP-4: the group popup of [groupId] edits a rule; [rule] is what it would save now (null: nothing valid and ticked). Outlines the
     * open tabs it matches (ungrouped, unpinned, not preview, every split: what POP-2 would move) in the group's colour, and the
     * matching files in the Project view. Null clears the preview. EDT.
     */
    fun previewRule(groupId: String, rule: AutoGroupRule?) = rules.preview(groupId, rule)

    /**
     * POP-4: what the preview shows for [rule]: per split, the open tabs it would move (ungrouped, unpinned, not preview; empty for an
     * inactive rule). [previewRule] hands it to the tab bar; the group popup lists the first of them under a regex (the match hint).
     */
    fun previewMatches(rule: AutoGroupRule): Map<SplitHandle, List<VirtualFile>> = rules.matchesOf(rule)

    /** POP-4: the rule preview being shown, for the Project view highlight (read off the EDT, so an immutable value in a volatile field). */
    val rulePreview: RulePreview? get() = rules.shownPreview

    /**
     * The group popup of [groupId], opened in [split], closed by Enter, Escape or a click outside (not by one of its actions), after
     * [applyRule]: clears the rule preview (POP-4); if the group then has an empty instance and no enabled rule, asks whether to keep
     * it (MEM-5 amended, [TabGroupsUi.askDeleteEmpty], honouring the remembered answer). EDT.
     */
    fun groupPopupClosed(split: SplitHandle, groupId: String) {
        rules.preview(groupId, null)
        empties.popupClosed(groupId)
    }

    /**
     * Group popup *Split Right* / *Split Down*: a new split beside [split] (right, or below if [down]) with a linked copy of [groupId]'s
     * instance ([TabBar.splitGroup]); the source keeps its instance. False if nothing could be done (no open tab). The service attaches
     * nothing itself: the copy's members arrive through the tab bar, as SPLIT-2 ([tabsOpened] for the files opened in the new split, each
     * joining the group's instance there) or as an arrival ([groupMovedIn]); both are handled as for a Ctrl-drop. EDT.
     */
    fun splitGroup(split: SplitHandle, groupId: String, down: Boolean): Boolean {
        if (state.instance(split, groupId) == null) return false
        attach()
        return tabBar.splitGroup(split, groupId, down)
    }

    // ---- stash (LIFE-2, LIFE-4, PERS-4, SET-9) ---------------------------------------------------------------------------

    /**
     * LIFE-2 Stash Group: closes [split]'s instance of [groupId] and its tabs, and saves a snapshot to the stash (name, colour, rule,
     * the ordered member files; an empty group with an enabled rule is stashed with no files, RULE-6). No confirmation (it can be undone with [restoreStashed]). Other splits are unaffected. The
     * closes leave no Reopen Closed Tab entries: the stash is how the group comes back. Publishes [TabGroupStashListener].
     */
    fun stashGroup(split: SplitHandle, groupId: String) = comeback.stashGroup(split, groupId)

    /** LIFE-4, SET-9: the stash, newest first. */
    fun stashedGroups(): List<StashedGroup> = comeback.stashedGroups()

    /**
     * LIFE-4: restores stash entry [entryId] into [split] (the current split; null if the project has no editor split, then the files
     * open in a new one). Files that still exist are reopened (PERS-4: missing ones are skipped, [TabGroupsUi.warnMissingFiles]), the
     * group is recreated (re-linked if it still exists in another split) at the end of the strip, and the entry leaves the stash.
     * Returns false if the entry is unknown. Publishes [TabGroupStashListener].
     */
    fun restoreStashed(split: SplitHandle?, entryId: String): Boolean = comeback.restoreStashed(split, entryId)

    /** LIFE-4 (Delete key in the popup), SET-9 (Remove button): removes entry [entryId] from the stash. Publishes [TabGroupStashListener]. */
    fun removeStashed(entryId: String) = comeback.removeStashed(entryId)

    /** Every change of a split's groups ends here: refreshes the strip, then updates the snapshot that is saved ([SavedGroups.capture]). */
    private fun refresh(split: SplitHandle, animate: Boolean = true) {
        tabBar.refresh(split, animate)
        saved.capture()
    }

    private fun refreshSplitsOf(groupId: String) {
        for (split in state.splits()) if (state.instance(split, groupId) != null) refresh(split)
    }

    /** Pinned tabs are refused (INV-3), so are tabs that are not showing; a preview tab becomes permanent (MEM-7). Attaches the host. */
    private fun prepare(split: SplitHandle, file: VirtualFile): Boolean {
        if (tabBar.isPinned(split, file) || file !in tabBar.visibleFiles(split)) return false
        if (tabBar.isPreview(split, file)) tabBar.makePermanent(split, file)
        attach()
        return true
    }

    /** Moves [file] to just after its current group in [split] (CTX-3, and before it joins another group), so that group stays contiguous. */
    private fun leaveGroup(split: SplitHandle, file: VirtualFile) {
        val old = state.groupOf(split, file) ?: return
        tabBar.reorder(split, GroupOrder.orderForLeave(tabBar.visibleFiles(split), file, old.members))
    }

    /**
     * A menu operation added an instance to [split] (appended to the state's list). [TabBarHost.groupsIn] must list the split's groups
     * in strip order, because collapsed groups next to each other share a host tab and are drawn in list order, so the instances are
     * re-sorted to the order of the headers in the strip ([TabBar.groupOrder]) and the split is refreshed again if that changed it.
     * Drag results need none of this: they report the new order themselves.
     */
    private fun arrange(split: SplitHandle, animate: Boolean = true) {
        val order = tabBar.groupOrder(split)
        if (order.isEmpty() || state.instancesIn(split).map { it.groupId } == order) return
        state.reorderInstances(split, order)
        refresh(split, animate)
    }

    private fun attach() {
        if (tabBar.host === this) return
        tabBar.style = currentStyle()
        tabBar.host = this
    }

    /** The strip look from the settings (SET-1, SET-2, SET-10). */
    private fun currentStyle() = StripStyle(settings.opacity, settings.nameLimit, settings.state.animateCollapse)

    /**
     * §14: the settings changed (the settings page's Apply); the look the tab bars cache is re-applied at once (no restart): tints
     * repaint with the new opacity, headers re-measure with the new name length. Everything else is read where it is used. Only once
     * attached; before that the first [attach] takes the current settings.
     */
    internal fun settingsChanged() {
        if (tabBar.host === this) tabBar.style = currentStyle()
    }

    // ---- persistence (PERS-1, PERS-3, MEM-4) ----------------------------------------------------------------------------

    /** The state of the workspace file: an immutable snapshot, safe to read on any thread (see the class comment). */
    override fun getState(): GroupsStateBean = saved.bean

    /** The platform loads the stored state once, before anything else uses the service; [restore] applies it later. */
    override fun loadState(state: GroupsStateBean) {
        saved.loaded(state)
        stash.replaceAll(GroupsPersistence.stashFromStored(GroupsPersistence.toStored(state).stash))
    }

    /**
     * Startup (PERS-3), called once the restored editor layout has settled ([dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.SplitsRestoredListener]).
     * Matches the stored splits to [splits] and, for each match, re-orders the tabs to the stored logical order (the platform reopened
     * the tabs of collapsed groups at the end), puts the instances with the members that reopened into the state in strip order, and
     * refreshes the split without animation (COLL-7). The stored state is replaced by what survived, so groups whose tabs did not
     * reopen are gone for good, also with "Reopen files on startup" off. Rules do not run on restored tabs (RULE-5).
     * A split in which the user already made a group before this ran is left as it is. EDT only; later calls do nothing.
     *
     * Late splits (a detached window that is restored after the layout settled): the stored splits of detached windows that no split
     * claimed stay pending for a while and are matched against splits that appear later ([LateSplitRestore]).
     */
    internal fun restore(splits: List<SplitInfo>) = groupsRestore.restore(splits)

    /** The project is about to be saved and closed: the saved state is taken now and kept (see [SavedGroups.freeze]). */
    internal fun prepareForClose() {
        saved.freeze()
        // SPLIT-6: the saved editor layout must not mention the placeholder tabs of temporary splits (the groups are in the state just frozen)
        project.getServiceIfCreated(TabBar::class.java)?.closePlaceholders()
    }

    /** MEM-4: files or directories were renamed or moved (old URL to new URL); see [MemberEvents.filesMoved]. EDT only. */
    internal fun filesMoved(moves: List<Pair<String, String>>) = memberEvents.filesMoved(moves)

    /** MEM-4: files were deleted; no member is left whose `VirtualFile` is invalid (MEM-5). EDT only. */
    internal fun dropInvalidMembers() = memberEvents.dropInvalidMembers()

    // ---- TabBarHost ----------------------------------------------------------------------------------------------------------

    override fun groupsIn(split: SplitHandle): List<GroupDecoration> {
        // IDE-2: unnamed groups sort in creation order, which is the order of the state's group list.
        val creation = state.groups.withIndex().associate { (i, g) -> g.id to i }
        return state.instancesIn(split).mapNotNull { inst ->
            val group = state.group(inst.groupId) ?: return@mapNotNull null
            GroupDecoration(group.id, group.name, colorOf(group), inst.members.toList(), inst.collapsed, creation[group.id] ?: Int.MAX_VALUE, inst.peek)
        }
    }

    /** COLL-6: a tab of a collapsed group picked in the overflow list: shown partially (COLL-8) and selected, like the hover list (INT-5). */
    /** COLL-8: see [HeaderInteractions.tabSelected]. */
    override fun tabSelected(split: SplitHandle, file: VirtualFile) = headers.tabSelected(split, file)

    override fun collapsedTabChosen(split: SplitHandle, groupId: String, file: VirtualFile) = openFromTabList(split, groupId, file)

    /** DRAG-*: the tab bar refreshes the split itself afterwards. MEM-7: a preview tab dropped into a group becomes permanent. */
    override fun groupsChangedByDrag(split: SplitHandle, groups: List<GroupDecoration>) = memberEvents.groupsChangedByDrag(split, groups)

    /**
     * INT-6, SPLIT-5: the tab bar refreshes the split itself afterwards. The arrival is appended to the state's list and the instances
     * are put in strip order ([LinkedJoins.groupMovedIn]).
     */
    override fun groupMovedIn(split: SplitHandle, group: GroupDecoration) = linked.groupMovedIn(split, group)

    /**
     * SPLIT-2, RULE-2: files opened in [split] that are members of a group in another split join that group here ([joinLinked]); the
     * files that are still ungrouped then go through the rules ([AutoGroupRules.applyRules]). While [split]
     * could still be restored from a pending stored split (a late detached window, see [restore]) its files are held back until the
     * restore check has run, so a restored window is not first filled by SPLIT-2.
     */
    override fun tabsOpened(split: SplitHandle, files: List<VirtualFile>) {
        if (!lateRestore.tabsOpened(split, files)) linked.joinLinkedThenRules(split, files)
    }

    /** RULE-2: a preview tab became a normal tab: SPLIT-2 and then the rules look at it, as for a file opened normally. */
    override fun tabMadePermanent(split: SplitHandle, file: VirtualFile) = linked.joinLinkedThenRules(split, listOf(file))

    /** MEM-1. */
    override fun tabClosed(split: SplitHandle, file: VirtualFile) = memberEvents.tabClosed(split, file)

    /** MEM-3: the platform moves the pinned tab itself (see [TabBarHost.tabPinned]); pinning a group's last tab asks like closing it. */
    override fun tabPinned(split: SplitHandle, file: VirtualFile) = memberEvents.tabPinned(split, file)

    /** COLL-4, unless SET-6 (auto-expand on navigate) is off. */
    override fun hiddenTabSelected(split: SplitHandle, groupId: String, file: VirtualFile) = headers.hiddenTabSelected(split, groupId, file)

    /** INT-5: the pointer rests on (or left) a header; the tab list is offered for a collapsed group (SET-7). */
    override fun headerHoverChanged(header: HeaderRef, hovering: Boolean) = headers.headerHoverChanged(header, hovering)

    /** INT-5: [file] was picked in the hover list of group [groupId] in [split]: the group expands and the tab is selected and focused. */
    fun openFromTabList(split: SplitHandle, groupId: String, file: VirtualFile) = headers.openFromTabList(split, groupId, file)

    /** INT-1: collapse / expand. */
    override fun headerClicked(header: HeaderRef, event: MouseEvent) = headers.headerClicked(header)

    /** INT-3: Delete Group, with the middle-click confirmation setting (SET-5). */
    override fun headerMiddleClicked(header: HeaderRef, event: MouseEvent) = headers.headerMiddleClicked(header)

    /** INT-4: the group popup. */
    override fun headerRightClicked(header: HeaderRef, event: MouseEvent) = headers.headerRightClicked(header)

    /** CTX-5: the free part of a tab strip was right-clicked: the *New Group* menu. */
    override fun stripRightClicked(split: SplitHandle, component: JComponent, point: Point) {
        TabGroupsUi.getInstance(project).showStripMenu(split, component, point)
    }

    override fun splitAppeared(split: SplitHandle) {
        lateRestore.splitAppeared(split)
    }

    /** SPLIT-6: the user closes a temporary split that still holds empty groups: they move to the split used last, or are deleted, with a warning. */
    override fun temporarySplitClosing(split: SplitHandle, groups: List<GroupDecoration>) = stranded.closing(split, groups)

    /** LIFE-5: true while a deleted group is recorded; keeps Reopen Closed Tab enabled when the platform's history is empty. Any thread. */
    override fun canReopenClosedGroup(): Boolean = comeback.hasClosedGroups

    /** LIFE-5: brings back the group deleted last in the split the action acts on, if no tab was closed after it; see [GroupComeback]. */
    override fun reopenClosedGroup(event: AnActionEvent): Boolean = comeback.reopenClosedGroup(event)

    /**
     * The split's instances go (MEM-6 may end groups). The tab bar calls this after the queued [tabsOpened] reports of the other splits
     * when there are any, so an unsplit (which moves the tabs of the merged-away split into the surviving one) still finds the
     * disposed split's instances when SPLIT-2 looks for the groups of those files; the disposed window is no longer in
     * [TabBar.recentSplits], so it ranks last among the candidates.
     */
    override fun splitDisposed(split: SplitHandle) {
        state.splitRemoved(split)
        commands.splitGone(split)
        empties.splitGone(split)
        lateRestore.splitDisposed(split)
        comeback.splitDisposed(split)
    }

    override fun dispose() {
        // Only detach on the EDT, and only if the tab bar still reports to this service.
        if (!ApplicationManager.getApplication().isDispatchThread) return
        val bar = project.getServiceIfCreated(TabBar::class.java) ?: return
        if (bar.host === this) bar.host = null
    }
}
