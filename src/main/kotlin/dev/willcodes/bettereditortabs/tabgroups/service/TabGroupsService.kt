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
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.model.groups.AutoGroupRule
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteStep
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteTrigger
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor
import dev.willcodes.bettereditortabs.tabgroups.model.groups.LinkedJoin
import dev.willcodes.bettereditortabs.tabgroups.model.groups.instanceStripOrder
import dev.willcodes.bettereditortabs.tabgroups.model.groups.linkedMoveTarget
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupOrder
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupStash
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.groups.InstanceLayout
import dev.willcodes.bettereditortabs.tabgroups.model.groups.TabGroup
import dev.willcodes.bettereditortabs.tabgroups.model.groups.deleteStep
import dev.willcodes.bettereditortabs.tabgroups.model.groups.nextColor
import dev.willcodes.bettereditortabs.tabgroups.model.persist.CurrentSplit
import dev.willcodes.bettereditortabs.tabgroups.model.persist.RestorePlan
import dev.willcodes.bettereditortabs.tabgroups.model.persist.RestorePlanner
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredInstance
import dev.willcodes.bettereditortabs.tabgroups.model.persist.colorFromName
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

    /** PERS-3 for detached windows restored after the first pass (see [restore]). Internal for tests. */
    internal val lateRestore = LateSplitRestore(
        this,
        saved,
        liveSplits = { SplitIdentity.allSplits(project) },
        isLive = { it in tabBar.splits() },
        hasInstances = { state.instancesIn(it).isNotEmpty() },
        apply = { plan, splits ->
            if (plan.splits.isNotEmpty()) attach()
            applyPlan(plan, splits)
        },
        joinLinked = ::joinLinkedThenRules,
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
        val color = nextColor(state.groups.map { it.color }, settings.fixedNewGroupColor)
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
        joinInstance(split, file, groupId, leave = true)
        return true
    }

    /**
     * [file] becomes the last member of [groupId]'s instance in [split] (created, at the tab's position, if the split has none) and its
     * tab moves behind the instance's last tab (INV-2). A collapsed instance is expanded first (CTX-2: the user sees where the tab
     * went). [leave]: the file leaves its current group first (CTX-2); false for a file that has none (SPLIT-2).
     */
    private fun joinInstance(split: SplitHandle, file: VirtualFile, groupId: String, leave: Boolean) {
        var target = state.instance(split, groupId)
        if (target != null && target.collapsed) {
            state.setCollapsed(split, groupId, false)
            refresh(split) // the group's tabs are visible again, so they take part in the order below
            target = state.instance(split, groupId)
        }
        if (leave) leaveGroup(split, file)
        if (target != null) tabBar.reorder(split, GroupOrder.orderForJoin(tabBar.visibleFiles(split), file, target.members))
        state.addToGroup(split, groupId, file)
        refresh(split)
        if (target == null) arrange(split)
    }

    /**
     * SPLIT-2: [files] were opened in [split] and are not members of a group there. A file that belongs to a group in another split
     * joins that group's instance here (created if the split has none); with different groups in different splits the one of the
     * most recently focused split wins ([LinkedJoin.pickGroup]). Pinned tabs (INV-3) and preview tabs (INV-4) stay out.
     */
    private fun joinLinked(split: SplitHandle, files: List<VirtualFile>) {
        if (tabBar.host !== this) return
        val others = state.splits().filter { it != split }
        if (others.isEmpty()) return
        val recent by lazy { tabBar.recentSplits() }
        val picks = ArrayList<LinkedJoin.Pick<VirtualFile>>()
        for (file in files) {
            if (!file.isValid) continue
            val candidates = others.mapNotNull { s -> state.groupOf(s, file)?.let { s to it.groupId } }
            if (candidates.isEmpty() || tabBar.openTabs(split, listOf(file)).isEmpty()) continue
            if (!LinkedJoin.eligible(tabBar.isPinned(split, file), tabBar.isPreview(split, file), state.groupOf(split, file) != null)) continue
            if (file !in tabBar.visibleFiles(split)) continue
            val groupId = LinkedJoin.pickGroup(candidates, recent) ?: continue
            val from = candidates.first { it.second == groupId }.first
            picks += LinkedJoin.Pick(file, groupId, state.instance(from, groupId)?.members?.indexOf(file) ?: Int.MAX_VALUE)
        }
        // several files at once (an unsplit): each group gets them at its end in the order they had where they came from
        for (pick in LinkedJoin.joinOrder(picks)) {
            if (state.groupOf(split, pick.file) == null) joinInstance(split, pick.file, pick.groupId, leave = false)
        }
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
     * instance in the most recently focused other split ([moveToLinked]); Close deletes this instance as usual; other splits' instances
     * are never touched by a delete.
     */
    fun requestDelete(split: SplitHandle, groupId: String, trigger: DeleteTrigger) {
        val group = state.group(groupId) ?: return
        val instance = state.instance(split, groupId) ?: return
        val tabCount = tabBar.openTabs(split, instance.members).size
        val ui = TabGroupsUi.getInstance(project)
        when (deleteStep(tabCount, settings.confirmsDelete(trigger), linkedSplits(split, groupId).isNotEmpty())) {
            DeleteStep.DELETE_NOW -> {}
            DeleteStep.CONFIRM -> when (ui.confirmDelete(displayName(group), tabCount)) {
                DeleteAnswer.CANCEL -> return
                DeleteAnswer.DELETE_AND_STOP_ASKING -> settings.stopConfirmingDelete(trigger)
                DeleteAnswer.DELETE -> {}
            }
            DeleteStep.LINKED_CHOICE -> when (linkedDeleteAnswer(displayName(group), tabCount)) {
                LinkedDeleteAnswer.CANCEL -> return
                LinkedDeleteAnswer.CLOSE -> {}
                LinkedDeleteAnswer.MOVE -> {
                    moveToLinked(split, groupId)
                    return
                }
            }
        }
        deleteInstance(split, groupId)
    }

    /** LIFE-3a: the remembered answer ("Don't ask again"), or the dialog's; a "Don't ask again" ticked with Move or Close is remembered. */
    private fun linkedDeleteAnswer(groupName: String, tabCount: Int): LinkedDeleteAnswer {
        settings.linkedDeleteChoice?.let { return it }
        val choice = TabGroupsUi.getInstance(project).askLinkedDelete(groupName, tabCount)
        if (choice.dontAskAgain) settings.rememberLinkedDelete(choice.answer)
        return choice.answer
    }

    /** The live splits other than [split] that have an instance of [groupId]. */
    private fun linkedSplits(split: SplitHandle, groupId: String): List<SplitHandle> {
        val live = tabBar.splits().toSet()
        return state.splits().filter { it != split && it in live && state.instance(it, groupId) != null }
    }

    /**
     * LIFE-3a "Move": the tabs of [split]'s instance go to the instance in the most recently focused other split. Re-reads the state
     * (the dialog was modal). The files that are open in the target already are just closed here (a normal close); the rest moves as a
     * group move (no Reopen Closed Tab history, merged at the end of the target's instance, which keeps its collapsed flag). Nothing
     * is done if no other split has the group any more.
     */
    private fun moveToLinked(split: SplitHandle, groupId: String) {
        val instance = state.instance(split, groupId) ?: return
        val target = linkedMoveTarget(tabBar.recentSplits(), linkedSplits(split, groupId), split) ?: return
        val duplicates = tabBar.openTabs(target, tabBar.openTabs(split, instance.members))
        if (duplicates.isNotEmpty()) {
            for (file in duplicates) state.removeFromGroup(split, file)
            refresh(split)
            tabBar.closeTabs(split, duplicates)
        }
        if (state.instance(split, groupId) == null) return
        tabBar.moveGroup(split, groupId, target)
    }

    /**
     * Removes the instance, then closes its open tabs without Reopen Closed Tab entries and records the group, so that Reopen Closed
     * Tab brings it back (LIFE-5, [GroupComeback.deleteGroup]). The state is re-read there because the confirmation is modal and may
     * have let things change. The instance goes first and the split is refreshed, so no tab is still hidden while it closes and the
     * tab bar no longer lists the files (MEM-1 [tabClosed] is not reported for them).
     */
    private fun deleteInstance(split: SplitHandle, groupId: String) = comeback.deleteGroup(split, groupId)

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

    /**
     * SPLIT-2 first (a file that belongs to a group in another split joins it: the linked join wins), then RULE-2 for what is still
     * ungrouped. The one entry for new and held-back tabs ([LateSplitRestore]) and for promoted preview tabs.
     */
    private fun joinLinkedThenRules(split: SplitHandle, files: List<VirtualFile>) {
        joinLinked(split, files)
        rules.applyRules(split, files)
    }

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
        tabBar.style = StripStyle(settings.opacity, settings.nameLimit, settings.state.animateCollapse)
        tabBar.host = this
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
    internal fun restore(splits: List<SplitInfo>) {
        if (saved.restored) return
        val stored = saved.stored()
        saved.markRestored()
        val current = splits.map { s -> CurrentSplit(s.key, s.files.map { it.url }) }
        val plan = RestorePlanner.plan(stored, current) // the stash is not part of it: it survives a restart whatever reopened (PERS-4)
        val pending = RestorePlanner.pending(stored, current, detachedOnly = true)
        if (plan.splits.isNotEmpty() || pending.splits.isNotEmpty()) attach()
        lateRestore.start(splits, pending)
        applyPlan(plan, splits)
        saved.capture()
    }

    /** Applies a [plan] made for [splits] (the index of a planned split is its place in the list): order, instances, refresh without animation (COLL-7). */
    private fun applyPlan(plan: RestorePlan, splits: List<SplitInfo>) {
        val groups = plan.groups.associateBy { it.id }
        for (planned in plan.splits) {
            val info = splits[planned.current]
            val split = info.handle
            if (state.instancesIn(split).isNotEmpty()) continue
            val byUrl = info.files.associateBy { it.url }
            tabBar.reorder(split, planned.order.mapNotNull { byUrl[it] })
            // IDE-2: before the groups arrive, so a split the plugin sorts starts from the manual order it had, not the sorted one
            if (planned.manualOrder.isNotEmpty()) tabBar.setManualOrder(split, planned.manualOrder.mapNotNull { byUrl[it] })
            val empties = ArrayList<StoredInstance>()
            for (inst in planned.instances) {
                val group = groups[inst.groupId] ?: continue
                val members = inst.members.mapNotNull { byUrl[it] }
                // PERS-3 amended: an instance stored empty comes back empty (kept as kept) and is placed once the split is refreshed
                if (inst.members.isEmpty()) empties += inst
                state.acceptInstance(split, group.id, group.name, colorFromName(group.color), members, inst.collapsed, group.rule?.toRule(), inst.members.isEmpty())
                // INV-4: a restored preview tab cannot be a member.
                for (file in members) if (tabBar.isPreview(split, file)) tabBar.makePermanent(split, file)
            }
            refresh(split, animate = false)
            for (inst in empties) tabBar.placeEmptyGroup(split, inst.groupId, inst.place ?: Int.MAX_VALUE)
            arrange(split, animate = false)
        }
    }

    /** The project is about to be saved and closed: the saved state is taken now and kept (see [SavedGroups.freeze]). */
    internal fun prepareForClose() {
        saved.freeze()
        // SPLIT-6: the saved editor layout must not mention the placeholder tabs of temporary splits (the groups are in the state just frozen)
        project.getServiceIfCreated(TabBar::class.java)?.closePlaceholders()
    }

    /**
     * MEM-4: files or directories were renamed or moved (old URL to new URL). The live state holds `VirtualFile`s, which keep their
     * identity, and the platform keeps the tab open (`FileEditorManagerImpl.MyVirtualFileListener`), so the groups follow by themselves;
     * only the stored form names files by URL, and so do the directory rules (RULE-1), which are rewritten live. EDT only.
     */
    internal fun filesMoved(moves: List<Pair<String, String>>) {
        for ((oldUrl, newUrl) in moves) state.rewriteRuleUrls(oldUrl, newUrl) // directory rules of the live groups follow too
        saved.filesMoved(moves)
    }

    /**
     * MEM-4: files were deleted. The platform has closed their tabs (which removed them from their groups, MEM-1); this makes sure no
     * member is left whose `VirtualFile` is invalid, and that an emptied group goes (MEM-5). EDT only.
     */
    internal fun dropInvalidMembers() {
        for (split in state.splits()) {
            val invalid = state.instancesIn(split).flatMap { inst -> inst.members.filter { !it.isValid } }
            if (invalid.isEmpty()) continue
            for (file in invalid) state.removeFromGroup(split, file)
            refresh(split)
        }
    }

    // ---- TabBarHost ----------------------------------------------------------------------------------------------------------

    override fun groupsIn(split: SplitHandle): List<GroupDecoration> {
        // IDE-2: unnamed groups sort in creation order, which is the order of the state's group list.
        val creation = state.groups.withIndex().associate { (i, g) -> g.id to i }
        return state.instancesIn(split).mapNotNull { inst ->
            val group = state.group(inst.groupId) ?: return@mapNotNull null
            GroupDecoration(group.id, group.name, colorOf(group), inst.members.toList(), inst.collapsed, creation[group.id] ?: Int.MAX_VALUE)
        }
    }

    /** COLL-6: a tab of a collapsed group picked in the overflow list: expand and select, like the hover list (INT-5). */
    override fun collapsedTabChosen(split: SplitHandle, groupId: String, file: VirtualFile) = openFromTabList(split, groupId, file)

    /** DRAG-*: the tab bar refreshes the split itself afterwards. MEM-7: a preview tab dropped into a group becomes permanent. */
    override fun groupsChangedByDrag(split: SplitHandle, groups: List<GroupDecoration>) {
        // DRAG-8 amended: a group whose only tab was dragged out keeps its (empty) instance for now; the user is asked afterwards
        val (keep, ask) = empties.emptiedByDrag(split, groups)
        state.replaceSplit(split, groups.map { InstanceLayout(it.id, it.members, it.collapsed) }, keep)
        for (group in groups) for (file in group.members) if (tabBar.isPreview(split, file)) tabBar.makePermanent(split, file)
        saved.captureLater()
        if (ask.isNotEmpty()) ApplicationManager.getApplication().invokeLater { empties.askAfterDrag(split, ask) }
    }

    /**
     * INT-6, SPLIT-5: the tab bar refreshes the split itself afterwards. The arrival is appended to the state's list, but
     * [groupsIn] must list a split's groups in strip order (collapsed neighbours share a host tab and are drawn in list order), so
     * the instances are put in the order of their first members in the split's tab order: the tab bar has re-ordered the target's
     * strip with everything visible before it reports the arrival.
     */
    override fun groupMovedIn(split: SplitHandle, group: GroupDecoration) {
        state.acceptInstance(split, group.id, group.name, GroupColors.colorOf(group.color), group.members, group.collapsed)
        val order = tabBar.logicalOrder(split)
        state.reorderInstances(split, instanceStripOrder(state.instancesIn(split).map { it.groupId to it.members.toList() }, order))
        saved.captureLater()
    }

    /**
     * SPLIT-2, RULE-2: files opened in [split] that are members of a group in another split join that group here ([joinLinked]); the
     * files that are still ungrouped then go through the rules ([AutoGroupRules.applyRules]). While [split]
     * could still be restored from a pending stored split (a late detached window, see [restore]) its files are held back until the
     * restore check has run, so a restored window is not first filled by SPLIT-2.
     */
    override fun tabsOpened(split: SplitHandle, files: List<VirtualFile>) {
        if (!lateRestore.tabsOpened(split, files)) joinLinkedThenRules(split, files)
    }

    /** RULE-2: a preview tab became a normal tab: SPLIT-2 and then the rules look at it, as for a file opened normally. */
    override fun tabMadePermanent(split: SplitHandle, file: VirtualFile) = joinLinkedThenRules(split, listOf(file))

    /** MEM-1. */
    override fun tabClosed(split: SplitHandle, file: VirtualFile) {
        // MEM-5 / RULE-6 amended: the last tab of a group was closed: the instance stays until the user has answered (after the event)
        val instance = state.groupOf(split, file)
        if (instance != null && instance.members.size == 1) empties.lastTabLeaving(split, instance.groupId, EmptyCase.CLOSED)
        removeAfterEvent(split, file)
    }

    /**
     * MEM-3: the platform moves the pinned tab itself (see [TabBarHost.tabPinned]). Pinning a group's last tab asks like closing it
     * (MEM-5 amended: whatever would remove a group by itself asks first).
     */
    override fun tabPinned(split: SplitHandle, file: VirtualFile) {
        val instance = state.groupOf(split, file)
        if (instance != null && instance.members.size == 1) empties.lastTabLeaving(split, instance.groupId, EmptyCase.PINNED)
        removeAfterEvent(split, file)
    }

    private fun removeAfterEvent(split: SplitHandle, file: VirtualFile) {
        state.removeFromGroup(split, file)
        refresh(split)
    }

    /** COLL-4, unless SET-6 (auto-expand on navigate) is off. */
    override fun hiddenTabSelected(split: SplitHandle, groupId: String, file: VirtualFile) {
        if (!settings.state.autoExpandOnNavigate) return
        state.setCollapsed(split, groupId, false)
        refresh(split)
    }

    /**
     * INT-5: the pointer rests on (or left) a header. The tab list is offered only for a collapsed group with open tabs, and only while
     * SET-7 is on; leaving or pressing the header always ends the hover.
     */
    override fun headerHoverChanged(header: HeaderRef, hovering: Boolean) {
        val ui = TabGroupsUi.getInstance(project)
        if (!hovering) {
            ui.hoverEnded(header)
            return
        }
        val instance = state.instance(header.split, header.groupId) ?: return
        if (!instance.collapsed || !settings.state.showHoverList) return
        val files = tabBar.openTabs(header.split, instance.members)
        if (files.isNotEmpty()) ui.showTabListOnHover(header, files)
    }

    /**
     * INT-5: [file] was picked in the hover list of group [groupId] in [split]. The group expands (animated, COLL-7) and the tab is
     * selected and focused. Expands even with SET-6 off: picking from the list is an explicit request, not a navigation.
     */
    fun openFromTabList(split: SplitHandle, groupId: String, file: VirtualFile) {
        val instance = state.instance(split, groupId) ?: return
        if (instance.collapsed) {
            state.setCollapsed(split, groupId, false)
            refresh(split)
        }
        tabBar.selectTab(split, file, true)
    }

    /** INT-1: collapse / expand. */
    override fun headerClicked(header: HeaderRef, event: MouseEvent) {
        val instance = state.instance(header.split, header.groupId) ?: return
        state.setCollapsed(header.split, header.groupId, !instance.collapsed)
        refresh(header.split)
    }

    /** INT-3: Delete Group, with the middle-click confirmation setting (SET-5). */
    override fun headerMiddleClicked(header: HeaderRef, event: MouseEvent) {
        requestDelete(header.split, header.groupId, DeleteTrigger.MIDDLE_CLICK)
    }

    /** INT-4: the group popup. */
    override fun headerRightClicked(header: HeaderRef, event: MouseEvent) {
        TabGroupsUi.getInstance(project).showGroupPopup(header)
    }

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
