package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.model.strip.DropRules
import dev.willcodes.bettereditortabs.tabgroups.model.strip.DropSlots
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EmptyPillSpots
import dev.willcodes.bettereditortabs.tabgroups.model.strip.Landing
import dev.willcodes.bettereditortabs.tabgroups.model.strip.OwnGroupStay
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PillCell
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripCell
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripItem
import dev.willcodes.bettereditortabs.tabgroups.model.strip.TabCell
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.ForeignAnchor
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripUnit
import java.awt.Point
import java.awt.Rectangle

/**
 * The decisions of a tab drag inside the strip (DRAG-1..DRAG-6), separated from the gesture ([TabDragController]): which slot and
 * group the pointer means, which order the strip takes for it, and which group membership results when the tab is dropped.
 *
 * The rules themselves are the pure model code ([DropSlots], [DropRules]); this class feeds them the live strip and the hold-to-join
 * timer state ([hold]) and reads the "keep pinned tabs on the left" setting (`AdvancedSettings`, public API). It works for a tab of
 * this strip and for a tab dragged in from another split alike (the placeholder rectangle is a parameter). The companion holds the
 * pieces that need no live state, which a drop in another split uses as well ([TabMove]). EDT only.
 */
internal class TabDropPlanner(private val dnd: TabBarDnd, private val hold: DragHold) {
    /**
     * What has been applied to the strip so far during the drag. [inFrontOfPill]: the group whose pill is the cell right behind the landing
     * slot (the tab lands in front of that pill), null if none; DRAG-8 uses it to keep an emptied group's pill behind the tab.
     */
    class Applied(val landing: Landing, val flat: List<VirtualFile>, val inFrontOfPill: String? = null)

    /** A member re-ordered within its own group stays in it at the trailing slot, and a hold there takes it out ([OwnGroupStay]). */
    private var stay = OwnGroupStay(null)

    /** The drag begins with the tab a member of [group] (null: ungrouped). */
    fun start(group: String?) {
        stay = OwnGroupStay(group)
    }

    /**
     * Works out where the dragged tab lands for the pointer at [screen]: the slot under the pointer ([DropSlots.select] against the
     * live layout, with the placeholder [gap] (the dragged tab's own rectangle, or the foreign gap's) at [slot]) and the group it
     * belongs to there (hold-to-join included).
     */
    fun landingFor(gap: Rectangle?, cells: List<StripCell<VirtualFile>>, pinned: Boolean, slot: Int, screen: Point): Landing {
        val p = dnd.toTabs(screen)
        val keepPinned = keepPinnedFirst()
        val leading = if (keepPinned) cells.takeWhile { it is TabCell && it.pinned }.size else 0
        val lo = if (keepPinned && !pinned) leading else 0
        val hi = if (keepPinned && pinned) leading else cells.size
        val raw = DropSlots.select(cells, gap, slot, p, dnd.horizontal, lo, hi, null, JBUI.scale(DropSlots.HOLD_ZONE_PX))
        fun landing(joining: String?) = DropRules.landing(cells, raw, pinned, keepPinned, stay.joining(joining))
        val first = landing(hold.joiningGroup)
        // hold-to-join only starts with the pointer near the group's last tab (Chrome: the tab has to be on the group's edge); a member
        // at its own group's end is held out only beyond that zone ([OwnGroupStay.holdGroup])
        val trailing = first.trailingGroup
        val last = if (trailing != null) cells.getOrNull(first.slot - 1)?.rect else null
        fun near(joined: Boolean) = trailing != null && last != null && DropSlots.nearLastTab(
            last, p, dnd.horizontal, JBUI.scale(DropSlots.HOLD_ZONE_PX), JBUI.scale(DropSlots.HOLD_RELEASE_SLACK_PX),
            joined = joined, multiRow = DropSlots.isMultiRow(cells, gap, dnd.horizontal),
        )
        hold.update(stay.holdGroup(trailing, nearEnd = near(hold.joiningGroup == trailing), beyondEnd = !near(joined = true)), screen)
        return landing(hold.joiningGroup).also { stay.update(it) }
    }

    /** The slot a tab that is not in this strip starts in, for the pointer at [screen] ([DropSlots.startSlot]). */
    fun startSlot(cells: List<StripCell<VirtualFile>>, screen: Point): Int = DropSlots.startSlot(cells, dnd.toTabs(screen), dnd.horizontal)

    private fun keepPinnedFirst(): Boolean = keepPinnedTabsFirst()

    companion object {
        /** The strip without the dragged tab, as cells for [DropRules]. */
        fun cellsOf(strip: List<StripUnit>, dragged: VirtualFile): List<StripCell<VirtualFile>> = cellsOf(strip, dragged, emptySet())

        /**
         * [cellsOf] where the pills of [parked] groups (DRAG-8: their only tab is the dragged one) count as empty pills: without the
         * dragged tab they have no tab, and the slot right behind such a narrow pill must be reachable like the one behind any empty
         * pill (round 2, `DropSlots.emptyTrailingSlot`), so the tab can come back into its own group (user report, round 5).
         */
        fun cellsOf(strip: List<StripUnit>, dragged: VirtualFile, parked: Set<String>): List<StripCell<VirtualFile>> = strip.mapNotNull { u ->
            when (u) {
                is StripUnit.Tab -> if (u.file == dragged) null else TabCell(u.file, u.groupId, u.pinned, Rectangle(u.rect))
                is StripUnit.Pill -> PillCell(u.groupId, u.collapsed, Rectangle(u.rect), u.empty || u.groupId in parked)
            }
        }

        /** The strip order after dropping [dragged] with [landing], collapsed groups' hidden members included ([groups] as they were at the start). */
        fun flatOrder(cells: List<StripCell<VirtualFile>>, landing: Landing, dragged: VirtualFile, groups: List<GroupDecoration>): List<VirtualFile> =
            DropRules.flatOrder(cells, landing, dragged) { gid -> membersOf(groups, gid) }

        /**
         * [flatOrder] with the pills of empty groups kept in it (RULE-6, see [DropRules.sequenceOrder]): where they stand relative to the
         * dropped tab, which [dev.willcodes.bettereditortabs.tabgroups.model.strip.EmptyPillSpots.derive] turns into anchors.
         */
        fun sequenceOrder(cells: List<StripCell<VirtualFile>>, landing: Landing, dragged: VirtualFile, groups: List<GroupDecoration>): List<StripItem<VirtualFile>> =
            DropRules.sequenceOrder(cells, landing, dragged) { gid -> membersOf(groups, gid) }

        /** [flatten] with the pills of empty groups kept in it (RULE-6): the strip's order as a sequence of items. */
        fun sequenceOf(strip: List<StripUnit>, groups: List<GroupDecoration>): List<StripItem<VirtualFile>> {
            val out = ArrayList<StripItem<VirtualFile>>()
            for (u in strip) when (u) {
                is StripUnit.Tab -> out += StripItem.Tab(u.file)
                is StripUnit.Pill -> if (u.collapsed) {
                    val members = membersOf(groups, u.groupId)
                    if (members.isEmpty()) out += StripItem.EmptyPill(u.groupId) else members.mapTo(out) { StripItem.Tab(it) }
                }
            }
            return out
        }

        /**
         * DRAG-8: where the pills of the groups that [dragged] was the only tab of stay after it is dropped ([afterDrop] has them without a
         * member): as anchors for [dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.TabBarAdapter.placeEmptyPills], for every empty
         * pill of the result. The pill stays right behind the item that stood in front of the tab when the drag began ([origSeq]), in
         * [landed], the sequence the drop leaves (the tab at its landing, the pills of other empty groups at theirs). Empty if no group
         * was emptied.
         *
         * A tab that lands at its old place again was dropped either in front of the pill ([inFrontOfPill] is that group: it leaves the
         * group to the front, the pill goes behind it) or at the group's trailing slot after the hold (DRAG-3: the pill stays in front of
         * it). User report (round 2): the front case used to put the pill in front of the tab, so the tab looked snapped back.
         */
        fun pillsKept(
            afterDrop: List<GroupDecoration>,
            before: List<GroupDecoration>,
            dragged: VirtualFile,
            origSeq: List<StripItem<VirtualFile>>,
            landed: List<StripItem<VirtualFile>>,
            inFrontOfPill: String? = null,
        ): Map<String, VirtualFile?> {
            val emptied = afterDrop.filter { g -> g.members.isEmpty() && before.any { it.id == g.id && dragged in it.members } }
            val at = origSeq.indexOfFirst { it is StripItem.Tab && it.key == dragged }
            if (emptied.isEmpty() || at < 0 || landed.isEmpty()) return emptyMap()
            val behind = origSeq.getOrNull(at - 1)
            val seq = ArrayList(landed)
            var index = if (behind == null) 0 else seq.indexOf(behind) + 1
            val tabThere = seq.getOrNull(index).let { it is StripItem.Tab && it.key == dragged }
            if (tabThere && emptied.any { it.id == inFrontOfPill }) index++ // dropped in front of the pill: the pill stays behind the tab
            for (g in emptied) seq.add(index++, StripItem.EmptyPill(g.id))
            return EmptyPillSpots.derive(seq)
        }

        /** The flat order of the strip as it was before the drag: tabs in strip order, a collapsed group's hidden members at its pill. */
        fun flatten(strip: List<StripUnit>, groups: List<GroupDecoration>): List<VirtualFile> {
            val out = ArrayList<VirtualFile>()
            for (u in strip) when (u) {
                is StripUnit.Tab -> out += u.file
                is StripUnit.Pill -> if (u.collapsed) out += membersOf(groups, u.groupId)
            }
            return out
        }

        /** The groups after [dragged] was dropped as [applied]: it left every group, joined the landing group, members follow the new order. */
        fun groupsAfterDrop(groups: List<GroupDecoration>, dragged: VirtualFile, applied: Applied): List<GroupDecoration> = groups.map { g ->
            val members = g.members.toMutableSet()
            members.remove(dragged)
            val joins = g.id == applied.landing.groupId
            // the first member of an empty group (held behind its pill, DRAG-3) shows the group expanded, whatever its flag said
            val wasEmpty = members.none { it in applied.flat }
            if (joins) members.add(dragged)
            g.copy(members = applied.flat.filter { it in members }, collapsed = if (joins && wasEmpty) false else g.collapsed)
        }

        /**
         * Where the space for a tab dragged in from another split opens ([StripDragSurface.setForeignGap]) for the slot [slot] of [cells]:
         * in front of the cell that follows it. In front of an expanded group's pill that is in front of its first tab's pill space; between a
         * pill and the tab behind it, behind the pills (DRAG-2).
         */
        fun anchorFor(cells: List<StripCell<VirtualFile>>, slot: Int): ForeignAnchor {
            val next = cells.getOrNull(slot) ?: return ForeignAnchor.AtEnd
            val prev = cells.getOrNull(slot - 1)
            return when (next) {
                is TabCell -> ForeignAnchor.BeforeTab(next.key, afterPills = prev is PillCell)
                is PillCell -> if (next.collapsed) ForeignAnchor.BeforeCollapsedGroup(next.groupId) else {
                    val first = cells.drop(slot + 1).firstOrNull { it is TabCell && it.groupId == next.groupId }
                        ?: cells.drop(slot + 1).firstOrNull { it is TabCell }
                    if (first is TabCell) ForeignAnchor.BeforeTab(first.key) else ForeignAnchor.AtEnd
                }
            }
        }

        private fun membersOf(groups: List<GroupDecoration>, id: String): List<VirtualFile> = groups.firstOrNull { it.id == id }?.members.orEmpty()

        /** The platform setting "keep pinned tabs on the left" (`AdvancedSettings`, public API); on if it cannot be read. */
        fun keepPinnedTabsFirst(): Boolean = try {
            AdvancedSettings.getBoolean("editor.keep.pinned.tabs.on.left")
        }
        catch (e: Exception) {
            true
        }
    }
}
