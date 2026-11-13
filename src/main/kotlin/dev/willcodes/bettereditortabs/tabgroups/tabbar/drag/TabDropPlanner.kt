package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.model.strip.DropRules
import dev.willcodes.bettereditortabs.tabgroups.model.strip.DropSlots
import dev.willcodes.bettereditortabs.tabgroups.model.strip.Landing
import dev.willcodes.bettereditortabs.tabgroups.model.strip.OwnGroupStay
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PillCell
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripCell
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
    /** What has been applied to the strip so far during the drag. */
    class Applied(val landing: Landing, val flat: List<VirtualFile>)

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
        val raw = DropSlots.select(cells, gap, slot, p, dnd.horizontal, lo, hi)
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
        fun cellsOf(strip: List<StripUnit>, dragged: VirtualFile): List<StripCell<VirtualFile>> = strip.mapNotNull { u ->
            when (u) {
                is StripUnit.Tab -> if (u.file == dragged) null else TabCell(u.file, u.groupId, u.pinned, Rectangle(u.rect))
                is StripUnit.Pill -> PillCell(u.groupId, u.collapsed, Rectangle(u.rect))
            }
        }

        /** The strip order after dropping [dragged] with [landing], collapsed groups' hidden members included ([groups] as they were at the start). */
        fun flatOrder(cells: List<StripCell<VirtualFile>>, landing: Landing, dragged: VirtualFile, groups: List<GroupDecoration>): List<VirtualFile> =
            DropRules.flatOrder(cells, landing, dragged) { gid -> membersOf(groups, gid) }

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
            if (g.id == applied.landing.groupId) members.add(dragged)
            g.copy(members = applied.flat.filter { it in members })
        }

        /**
         * Where the space for a tab dragged in from another split opens ([TabBarAdapter.setForeignGap]) for the slot [slot] of [cells]:
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
