package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.impl.JBEditorTabs
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EmptyPillSpots
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripItem
import dev.willcodes.bettereditortabs.tabgroups.model.strip.files
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration

/**
 * The bookkeeping of empty-group pills (where a stored empty group stands, and what a drag or the
 * sort asks of them): remembering where a group's tabs stand so its pill stays when the last tab goes, keeping the pills out of other
 * groups' spans, seating a group's first members behind its pill, and placing pills behind given tabs. The places themselves live in
 * [CollapseController]; this class decides when and where.
 */
internal class EmptyPills(
    private val tabs: JBEditorTabs,
    private val alive: () -> Boolean,
    private val collapse: CollapseController,
    private val applier: GroupsApplier,
    private val preview: DragPreview,
    private val transit: TabTransit,
    private val drag: () -> StripDragSurface,
    private val dragActive: () -> Boolean,
    /** True while the plugin sorts the strip (the sort places the pills then). */
    private val sortOwns: () -> Boolean,
    private val visibleFiles: () -> List<VirtualFile>,
    private val updateReservations: () -> Boolean,
) {
    /** What runs before new groups are applied (see [GroupsApplier.applyGroups]). */
    fun beforeGroups(newGroups: List<GroupDecoration>) {
        captureSpots()
        seatFirstMembers(newGroups)
        keepEmptyOutsideGroups(newGroups)
    }

    /**
     * Remembers where every expanded group's tabs stand (the tab in front of its first tab), so that the pill of a group that loses
     * its last tab stays there ([CollapseController.captureSpots]). Not while a drag runs: the drag moves tabs live, and the result
     * of a drop reaches the strip as new groups after the drag state is gone.
     */
    fun captureSpots() {
        if (!isQuiet()) return
        collapse.captureSpots(applier.shown, visibleFiles())
    }

    /** No drag moves tabs around here (ours, the platform's, a foreign gap, a previewed membership or a tab in transit). */
    private fun isQuiet(): Boolean = alive() && !dragActive() && !drag().isForeignBusy() && drag().platformDraggedFile() == null &&
        preview.draggedFile == null && !preview.hasMembershipPreview && transit.state == null

    /**
     * An empty pill never stands inside another group's span. A tab that a rule or the join path adds to a group at
     * its end lands behind the empty pill that stood behind the group's old last tab (`[G a b][E] c`); with [newGroups] applied the pill
     * would be in the middle of G. It is put behind G's new last tab instead ([EmptyPillSpots.outsideSpans]), before the groups are
     * applied. Not during a drag (the live order is a preview) and not while the plugin sorts the strip (the sort places the pills).
     */
    private fun keepEmptyOutsideGroups(newGroups: List<GroupDecoration>) {
        if (!isQuiet() || sortOwns() || collapse.emptyGroups().isEmpty()) return
        val groupOf = HashMap<VirtualFile, String>()
        for (g in newGroups) for (f in g.members) groupOf.putIfAbsent(f, g.id)
        val sequence = collapse.sequence(visibleFiles())
        val fixed = EmptyPillSpots.outsideSpans(sequence) { groupOf[it] }
        if (fixed === sequence) return
        collapse.placeEmpty(EmptyPillSpots.derive(fixed))
        updateReservations()
    }

    /**
     * An empty group that gets its first member(s) keeps its pill where it was and the new members stand right behind
     * the pill, in front of whatever followed it (other empty pills included). The strip is put in that order before the groups are
     * applied: the members come from wherever the platform or the host's `openInOrder` put them, the pill's place is the sequence's
     * ([CollapseController.sequence]), and the empty pills that stood behind it are anchored behind the arrivals. Nothing animates.
     * A collapsed group keeps its pill and its anchor as it is: its members are hidden at the pill's place anyway. Not while the plugin
     * sorts the strip (the sort puts the group among the groups by name).
     */
    private fun seatFirstMembers(newGroups: List<GroupDecoration>) {
        if (!alive() || sortOwns()) return
        val empty = collapse.emptyGroups()
        if (empty.isEmpty()) return
        val visible = visibleFiles()
        val arrivals = LinkedHashMap<String, List<VirtualFile>>()
        for (g in newGroups) {
            if (g.id !in empty || g.collapsed) continue
            val members = g.members.toHashSet()
            val here = visible.filter { it in members } // the strip's own order
            if (here.isNotEmpty()) arrivals[g.id] = here
        }
        if (arrivals.isEmpty()) return
        val seated = arrivals.values.flatMapTo(HashSet()) { it }
        val items = ArrayList<StripItem<VirtualFile>>()
        for (item in collapse.sequence(visible)) when {
            item is StripItem.Tab && item.key in seated -> Unit
            item is StripItem.EmptyPill && item.groupId in arrivals -> arrivals.getValue(item.groupId).mapTo(items) { StripItem.Tab(it) }
            else -> items += item
        }
        collapse.applyOrder(items.files())
        collapse.placeEmpty(EmptyPillSpots.derive(items))
        updateReservations()
    }

    /** The ids of the groups the strip shows as their pill alone, as of the last group change. */
    fun emptyGroupIds(): Set<String> = collapse.emptyGroups()

    /**
     * Moves the pills of empty groups behind the tabs [spots] gives (see [CollapseController.placeEmpty]); what a drag or the
     * sort does after it has put the tabs into the order it wants. Ids that are not empty groups are ignored. Lays the strip out again.
     */
    fun placeEmptyPills(spots: Map<String, VirtualFile?>) = applier.own {
        if (!alive() || spots.isEmpty()) return@own
        collapse.placeEmpty(spots)
        updateReservations()
        tabs.revalidate()
        tabs.repaint()
    }

    /**
     * Puts the pill of the empty group [groupId] behind the first [index] tabs of the logical order (0 = in front of all,
     * past the end = at the end), never inside another group's span (the pill goes behind that group, [keepEmptyOutsideGroups]). Not an
     * empty group of this strip: no-op.
     */
    fun placeEmptyAt(groupId: String, index: Int) = applier.own {
        if (!alive() || groupId !in collapse.emptyGroups()) return@own
        val items = collapse.sequence(visibleFiles()).filterNot { it is StripItem.EmptyPill && it.groupId == groupId }.toMutableList()
        var at = if (index <= 0) 0 else items.size
        if (index > 0) {
            var tabsSeen = 0
            for ((i, item) in items.withIndex()) if (item is StripItem.Tab && ++tabsSeen == index) {
                at = i + 1
                break
            }
        }
        // behind the pills already standing there: several empty groups stored at one place come back in their stored order
        while (at < items.size && items[at] is StripItem.EmptyPill) at++
        items.add(at, StripItem.EmptyPill(groupId))
        val members = HashMap<VirtualFile, String>()
        for (g in applier.groups) for (f in g.members) members.putIfAbsent(f, g.id)
        collapse.placeEmpty(EmptyPillSpots.derive(EmptyPillSpots.outsideSpans(items) { members[it] }))
        updateReservations()
        tabs.revalidate()
        tabs.repaint()
    }

    /** The anchor (the tab it stands behind) of every empty group's pill, null = in front of all tabs (see [CollapseController.emptyAnchors]). */
    fun emptyAnchors(): Map<String, VirtualFile?> = collapse.emptyAnchors()

    /**
     * Where [groupId]'s header stands: the number of tabs of the logical order in front of it (the index of the first tab of a group with
     * tabs, the place of the pill of an empty group), null if this strip has no header for the group (not one of the groups last set).
     */
    fun headerIndex(groupId: String): Int? {
        val g = applier.groups.firstOrNull { it.id == groupId } ?: return null
        val sequence = collapse.sequence(visibleFiles())
        val pill = sequence.indexOfFirst { it is StripItem.EmptyPill && it.groupId == groupId }
        if (pill >= 0) return sequence.subList(0, pill).files().size
        val members = g.members.toHashSet()
        return sequence.files().indexOfFirst { it in members }.takeIf { it >= 0 }
    }
}
