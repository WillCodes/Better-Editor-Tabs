package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.groups.TabGroup
import dev.willcodes.bettereditortabs.tabgroups.model.groups.nextColor
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle

/**
 * Empty groups (CTX-5, MEM-1, MEM-5 amended 2026-11-14, RULE-6 amended, DRAG-8 amended) for [TabGroupsService], which delegates to it:
 * *New Group* ([create]), and the question whether to keep a group that is left empty, for every way a group ends up empty: its last
 * tab was closed or pinned ([lastTabLeaving], MEM-1, MEM-3), dragged out ([emptiedByDrag], DRAG-8), or its popup closed while empty and without an enabled
 * rule ([popupClosed]; a ruled group is empty on purpose and not asked then). Groups with an enabled rule are asked too, with their
 * own wording and their own remembered answer ([TabGroupsSettings.emptyRuledGroupChoice] / [TabGroupsSettings.emptyGroupChoice]).
 *
 * The question always comes after the event: the instance survives until it is answered (an unruled group gets [TabGroup.keepWhileEmpty]
 * provisionally, a ruled one is kept by its rule), the asks are queued ([schedule]) and run in one `invokeLater` batch, one question per
 * group however many of its tabs or splits were involved (Close All, Close Others). Nothing is asked for a split that is gone, for a
 * group that has a member again, for an instance that was empty before the event (a drag that does not touch it), during the project
 * close ([SavedGroups.isFrozen], disposal), and never for groups removed by our own operations (Stash, Delete Group remove the instance
 * first). Pinning the last tab (MEM-3) is not closing: that group is removed as before.
 *
 * A kept group has [TabGroup.keepWhileEmpty] set (session only; the instance itself is stored empty, PERS-3 amended), which exempts it
 * from MEM-5 until a tab joins it. Shares the service's [state]; [refresh] and [arrange] are the service's own. EDT only.
 */
internal class EmptyGroups(
    private val project: Project,
    private val state: GroupsState<SplitHandle, VirtualFile>,
    private val saved: SavedGroups,
    private val refresh: (SplitHandle) -> Unit,
    private val arrange: (SplitHandle) -> Unit,
    private val isLive: (SplitHandle) -> Boolean,
) {
    private val settings get() = TabGroupsSettings.getInstance()

    /** The questions waiting for the end of the current event: per group, the case of the first and the splits with an empty instance. */
    private val pending = LinkedHashMap<String, Pending>()
    private var drainQueued = false

    private class Pending(val case: EmptyCase, val splits: LinkedHashSet<SplitHandle> = LinkedHashSet())

    /** CTX-5: see [TabGroupsService.createEmptyGroup]; the caller has attached the host. */
    fun create(split: SplitHandle): TabGroup {
        val color = nextColor(state.groups.map { it.color }, settings.fixedNewGroupColor)
        val group = state.createEmptyGroup(split, color)
        refresh(split)
        arrange(split)
        return group
    }

    /** MEM-5 amended: the group popup of [groupId] closed; asks about its empty instances if it has no enabled rule (a ruled one is empty on purpose). */
    fun popupClosed(groupId: String) {
        val group = state.group(groupId) ?: return
        if (group.hasEnabledRule) return
        val empty = state.splits().filter { state.instance(it, groupId)?.members?.isEmpty() == true }
        if (empty.isNotEmpty()) resolve(groupId, empty, EmptyCase.POPUP_CLOSED)
    }

    /**
     * MEM-1 / MEM-3: the last member of [groupId]'s instance in [split] is being closed or pinned ([case]: [EmptyCase.CLOSED] or
     * [EmptyCase.PINNED]). Call before the state removes it. Unruled group: a
     * remembered Delete lets MEM-5 remove it at once, otherwise it is kept provisionally (and asked about after the event unless Keep is
     * remembered). Ruled group: the rule keeps the instance; asked after the event unless Keep is remembered.
     */
    fun lastTabLeaving(split: SplitHandle, groupId: String, case: EmptyCase) {
        if (saved.isFrozen || project.isDisposed) return
        val group = state.group(groupId) ?: return
        val ruled = group.hasEnabledRule
        val remembered = settings.emptyChoice(ruled)
        if (!ruled) {
            if (remembered == EmptyGroupAnswer.DELETE) return
            group.keepWhileEmpty = true
            if (remembered == EmptyGroupAnswer.KEEP) return
        }
        else if (remembered == EmptyGroupAnswer.KEEP) return
        schedule(split, groupId, case)
    }

    /**
     * DRAG-8 amended: of [groups] (a drag result for [split]), the groups that the drag left empty (they had members before): first the
     * ones whose empty instance must survive the state update for now (unruled, no Delete remembered), then the ones to ask about after
     * the drop ([askAfterDrag]). A ruled group's instance stays by its rule. Groups whose instance was empty already are not touched.
     */
    fun emptiedByDrag(split: SplitHandle, groups: List<GroupDecoration>): Pair<List<String>, List<String>> {
        val keep = ArrayList<String>()
        val ask = ArrayList<String>()
        for (g in groups) {
            if (g.members.isNotEmpty()) continue
            val group = state.group(g.id) ?: continue
            if (state.instance(split, g.id)?.members.isNullOrEmpty()) continue
            val ruled = group.hasEnabledRule
            val remembered = settings.emptyChoice(ruled)
            if (!ruled) {
                if (remembered == EmptyGroupAnswer.DELETE) continue
                keep += g.id
                if (remembered == null) ask += g.id
            }
            else if (remembered != EmptyGroupAnswer.KEEP) ask += g.id
        }
        return keep to ask
    }

    /** DRAG-8 amended: asks (after the event) about each group that was left empty by a drag and still is. */
    fun askAfterDrag(split: SplitHandle, groupIds: List<String>) {
        for (id in groupIds) schedule(split, id, EmptyCase.DRAGGED_OUT)
    }

    /** [split] is gone: nothing is asked about it. */
    fun splitGone(split: SplitHandle) {
        for (p in pending.values) p.splits.remove(split)
    }

    /** Queues a question about [groupId]'s empty instance in [split] for the end of the event; one batch, one question per group. */
    private fun schedule(split: SplitHandle, groupId: String, case: EmptyCase) {
        pending.getOrPut(groupId) { Pending(case) }.splits.add(split)
        if (drainQueued) return
        drainQueued = true
        ApplicationManager.getApplication().invokeLater({ drain() }, project.disposed)
    }

    private fun drain() {
        drainQueued = false
        val batch = pending.entries.map { it.key to it.value }
        pending.clear()
        if (project.isDisposed || saved.isFrozen) return
        for ((id, p) in batch) {
            val splits = p.splits.filter { isLive(it) && state.instance(it, id)?.members?.isEmpty() == true }
            if (splits.isNotEmpty()) resolve(id, splits, p.case)
        }
    }

    /**
     * Decides what happens to the empty instances of [groupId] in [splits]: the remembered answer for its kind (ruled or not), else
     * [TabGroupsUi.askDeleteEmpty] ("Don't ask again" remembers it). Delete removes the instances (MEM-6 may end the group); Keep sets
     * [TabGroup.keepWhileEmpty]. The state is re-read after the modal question.
     */
    private fun resolve(groupId: String, splits: List<SplitHandle>, case: EmptyCase) {
        val group = state.group(groupId) ?: return
        val ruled = group.hasEnabledRule
        var answer = settings.emptyChoice(ruled)
        if (answer == null) {
            val choice = TabGroupsUi.getInstance(project).askDeleteEmpty(groupDisplayName(group.name, group.color), ruled, case)
            if (choice.dontAskAgain) settings.rememberEmpty(ruled, choice.answer)
            answer = choice.answer
        }
        val live = state.group(groupId) ?: return
        val still = splits.filter { isLive(it) && state.instance(it, groupId)?.members?.isEmpty() == true }
        when (answer) {
            EmptyGroupAnswer.KEEP -> {
                if (still.isNotEmpty()) live.keepWhileEmpty = true
                saved.capture()
            }
            EmptyGroupAnswer.DELETE -> {
                live.keepWhileEmpty = false
                for (split in still) {
                    state.removeInstance(split, groupId)
                    refresh(split)
                }
            }
        }
    }
}
