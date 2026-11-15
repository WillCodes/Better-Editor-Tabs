package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.groups.AutoGroupRule
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.groups.PlacedInstance
import dev.willcodes.bettereditortabs.tabgroups.model.groups.RuleMatching
import dev.willcodes.bettereditortabs.tabgroups.model.groups.RulePlacement
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import java.awt.Color

/**
 * The auto-group rules for [TabGroupsService], which delegates to it: setting a group's rule ([applyRule]),
 * the "move matching tabs?" offer ([ruleMatches], [moveRuleMatches]) and pulling newly permanent tabs into the group whose rule
 * matches first ([applyRules]). Matching and placement are pure ([RuleMatching], [RulePlacement]); what a rule knows about a file
 * comes from [RuleFiles]. Shares the service's [state]; [refresh] and [arrange] are the service's own, [isHost] tells whether the
 * service is the tab bar's host. EDT only.
 */
internal class AutoGroupRules(
    private val project: Project,
    private val state: GroupsState<SplitHandle, VirtualFile>,
    private val saved: SavedGroups,
    private val refresh: (SplitHandle) -> Unit,
    private val arrange: (SplitHandle) -> Unit,
    private val isHost: () -> Boolean,
) {
    private val tabBar get() = project.getService(TabBar::class.java)

    /** See [TabGroupsService.applyRule]. */
    fun applyRule(groupId: String, rule: AutoGroupRule?, offerMatching: Boolean) {
        val group = state.group(groupId) ?: return
        val splits = state.splits().filter { state.instance(it, groupId) != null }
        group.rule = rule
        // A popup close keeps an empty instance whose rule went off; groupPopupClosed asks about it.
        if (rule?.enabled != true && !offerMatching) state.pruneEmpty(groupId)
        for (split in splits) refresh(split)
        saved.capture()
        val ask = TabGroupsSettings.getInstance().state.askToMoveMatchingTabs
        if (!offerMatching || rule?.isActive != true || !ask) return
        val count = ruleMatches(groupId).values.sumOf { it.size }
        if (count == 0) return
        if (TabGroupsUi.getInstance(project).askMoveMatching(groupDisplayName(group.name, group.color), count)) moveRuleMatches(groupId)
    }

    /** See [TabGroupsService.ruleMatches]. */
    fun ruleMatches(groupId: String): Map<SplitHandle, List<VirtualFile>> {
        val rule = state.group(groupId)?.rule?.takeIf { it.isActive } ?: return emptyMap()
        return matchesOf(rule)
    }

    /** Per live split, the open tabs (logical order) that [rule] would move: ungrouped, unpinned, not preview. Empty for an inactive rule. */
    fun matchesOf(rule: AutoGroupRule): Map<SplitHandle, List<VirtualFile>> {
        if (!rule.isActive) return emptyMap()
        val result = LinkedHashMap<SplitHandle, List<VirtualFile>>()
        for (split in tabBar.splits()) {
            val files = tabBar.logicalOrder(split).filter { file ->
                file.isValid && isRuleCandidate(split, file) && RuleMatching.matches(rule, RuleFiles.of(project, file))
            }
            if (files.isNotEmpty()) result[split] = files
        }
        return result
    }

    /** See [TabGroupsService.moveRuleMatches]. One re-ordering, refresh and arrangement per split. */
    fun moveRuleMatches(groupId: String) {
        if (state.group(groupId) == null) return
        for ((split, files) in ruleMatches(groupId)) pullIn(split, groupId, files)
    }

    /**
     * [files] became permanent tabs of [split]; each one that is visible, unpinned, not a preview tab and ungrouped
     * goes into the group whose rule matches first: the split's groups in strip order, then the project's other groups in creation
     * order ([RuleMatching.firstMatch]). The service calls this only for opened tabs and promoted preview tabs, never
     * for restored tabs, drags, ungrouping or unpinning.
     */
    fun applyRules(split: SplitHandle, files: List<VirtualFile>) {
        if (!isHost() || state.groups.none { it.rule?.isActive == true }) return
        for (file in files) {
            if (!file.isValid || !isRuleCandidate(split, file) || file !in tabBar.visibleFiles(split)) continue
            val inSplit = stripOrder(split).mapNotNull { state.group(it) }
            val inSplitIds = inSplit.mapTo(HashSet()) { it.id }
            val others = state.groups.filter { it.id !in inSplitIds }
            val hit = RuleMatching.firstMatch(inSplit, others, RuleFiles.of(project, file)) ?: continue
            pullIn(split, hit.group.id, listOf(file))
        }
    }

    /** True for a tab the rules may move: open, unpinned, not a preview tab and in no group of [split]. */
    private fun isRuleCandidate(split: SplitHandle, file: VirtualFile): Boolean =
        state.groupOf(split, file) == null && tabBar.openTabs(split, listOf(file)).isNotEmpty() &&
            !tabBar.isPinned(split, file) && !tabBar.isPreview(split, file)

    /** The ids of [split]'s instances in strip order: the headers the tab bar shows, then any instance it does not show (yet). */
    private fun stripOrder(split: SplitHandle): List<String> {
        val shown = tabBar.groupOrder(split)
        return shown + state.instancesIn(split).map { it.groupId }.filter { it !in shown }
    }

    /**
     * Makes [files] (open, ungrouped tabs of [split]) the last members of [groupId]'s instance there, moving their tabs behind it
     * ([RulePlacement]): an existing instance gets them at its end (a collapsed one expands first, so the user sees where the tabs went), a group without one gets
     * a new instance after the split's existing groups. The tabs are moved with [TabBar.openInOrder] (open files only move;
     * it takes the whole logical order). The selected tab stays selected.
     */
    private fun pullIn(split: SplitHandle, groupId: String, files: List<VirtualFile>) {
        if (files.isEmpty() || state.group(groupId) == null) return
        val selected = tabBar.selectedFile(split)
        if (state.instance(split, groupId)?.collapsed == true) {
            state.setCollapsed(split, groupId, false)
            refresh(split) // the group's tabs are visible again, so they take part in the order below
        }
        val order = tabBar.logicalOrder(split)
        val instances = stripOrder(split).mapNotNull { id ->
            val inst = state.instance(split, id) ?: return@mapNotNull null
            PlacedInstance(id, inst.members.toList(), headerIndexOf(split, id, inst.members, order))
        }
        val newOrder = RulePlacement.place(order, instances, groupId, files)
        if (tabBar.openInOrder(split, emptyList(), newOrder) == null) return
        for (file in files) state.addToGroup(split, groupId, file)
        refresh(split)
        arrange(split)
        if (selected != null && tabBar.selectedFile(split) != selected) tabBar.selectTab(split, selected, false)
    }

    /** [TabBar.headerIndex]; if the tab bar shows no header (yet): the index of the first member, the end of the order for an empty instance. */
    private fun headerIndexOf(split: SplitHandle, groupId: String, members: List<VirtualFile>, order: List<VirtualFile>): Int =
        tabBar.headerIndex(split, groupId) ?: members.firstNotNullOfOrNull { m -> order.indexOf(m).takeIf { it >= 0 } } ?: order.size

    // ---- the rule preview ----------------------------------------------------------------------------------------------

    /** The preview being shown (read off the EDT by the Project view highlight, so an immutable value in a volatile field). */
    @Volatile
    var shownPreview: RulePreview? = null
        private set

    private var projectViewRefreshQueued = false

    /** See [TabGroupsService.previewRule]; a null or inactive [rule] (or an unknown group) clears the preview. */
    fun preview(groupId: String, rule: AutoGroupRule?) {
        val group = state.group(groupId)
        val active = rule?.takeIf { it.isActive }
        if (group == null || active == null) {
            if (shownPreview == null) return // nothing shown, nothing to clear
            tabBar.setRulePreview(emptyMap(), null)
            shownPreview = null
            refreshProjectView()
            return
        }
        val color = GroupColors.jbColor(group.color)
        tabBar.setRulePreview(matchesOf(active), color)
        shownPreview = RulePreview(active, color)
        refreshProjectView()
    }

    /** One Project view refresh per burst of preview changes (a regex typed letter by letter); never creates the Project view. */
    private fun refreshProjectView() {
        if (projectViewRefreshQueued) return
        projectViewRefreshQueued = true
        ApplicationManager.getApplication().invokeLater {
            projectViewRefreshQueued = false
            if (!project.isDisposed) project.getServiceIfCreated(ProjectView::class.java)?.refresh()
        }
    }
}

/** The rule being previewed by the group popup and the colour of its group, for the Project view highlight ([TabGroupsService.rulePreview]). */
class RulePreview(val rule: AutoGroupRule, val color: Color)
