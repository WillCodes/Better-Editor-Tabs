package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.MouseDragHelper
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.model.strip.DropRules
import dev.willcodes.bettereditortabs.tabgroups.model.strip.DropSlots
import dev.willcodes.bettereditortabs.tabgroups.model.strip.HeaderDragGeometry
import dev.willcodes.bettereditortabs.tabgroups.model.strip.MoveUnit
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripCell
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripDragGeometry
import dev.willcodes.bettereditortabs.tabgroups.model.strip.TabCell
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripUnit
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.TabBarAdapter
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.JComponent

private val LOG = logger<HeaderDragHelper>()

/**
 * Drags a whole group by its header (INT-6, SPLIT-5).
 *
 * Wraps `MouseDragHelper` (public API): it listens on the glass pane, so the drag continues when the pointer leaves the header,
 * and gives the click-versus-drag dead zone, Esc to cancel and the Alt-only-DnD setting for free. It never starts on a tab
 * label (that is the plugin's own tab drag, [TabDragController]; the two never fight because a press on a pill is not a press on a
 * label). Drag-out mode of `MouseDragHelper` is not used: leaving the strip is just another kind of target here.
 *
 * Looks like a tab drag (the platform's, see [TabDragController]): one picture of the group (only its pill) floats centred on the
 * pointer, and in this split's strip the group's place is an empty slot ([TabBarAdapter.setDragGap], covering the pill and the
 * tabs) that moves live between the other units while the strip slides around it. The units (one per ungrouped tab, one per group,
 * a collapsed group is its pill) are crossed with the same rule as tabs ([DropSlots.select]), so the slot cannot flip back and
 * forth. Esc, or leaving every strip, puts the strip back as it was.
 *
 * Targets, from the pointer position:
 * - this split's strip: the slot above, never inside a group; dropping commits the order;
 * - another split's strip: the same kind of gap opens there and follows the pointer ([ForeignGapSession]), or, if that split
 *   already has an instance of the group, that instance is outlined (the drop merges);
 * - anywhere else: no target (a detached window is not implemented yet, see notes).
 */
internal class HeaderDragHelper(private val dnd: TabBarDnd, parent: Disposable) : MouseDragHelper<JBEditorTabs>(parent, dnd.tabs) {
    private class Target(
        val dnd: TabBarDnd,
        val units: List<MoveUnit<VirtualFile>>,
        val boundary: Int,
        val merge: Boolean,
    )

    private var pressedGroup: String? = null

    /** The units as they were when the drag began (the live strip is re-ordered while the group is dragged inside this split). */
    private var startUnits: List<MoveUnit<VirtualFile>> = emptyList()
    private var from = -1
    private var minBoundary = 0
    private var target: Target? = null
    private val feedback = GroupDragFeedback(dnd)

    private var origGroups: List<GroupDecoration> = emptyList()
    private var origFlat: List<VirtualFile> = emptyList()
    private var shownFlat: List<VirtualFile> = emptyList()

    /** The group's own tabs (the placeholder covers them and the pill). */
    private var groupFiles: List<VirtualFile> = emptyList()

    /** The live-moving placeholder is shown in this split's strip (the pointer is over it). */
    private var gapShown = false

    /** The size of the dragged group as it was when the drag began; null outside a drag. */
    private var size: DraggedGroupSize? = null

    /** The gap opened for the group in another split's strip. */
    private val foreign = ForeignGapSession()

    /**
     * Everything this strip covered on screen at any moment of this drag. Moving the group changes the number of rows of a wrapped
     * strip, so the live strip rectangle shrinks and grows because of the drag itself; "has the pointer left this strip" is judged
     * against the widest it has been (plus [ForeignGapSession.STICKY_MARGIN_PX] across the strip), which cannot flip back and forth.
     */
    private var ownReach: Rectangle? = null

    override fun canStartDragging(dragComponent: JComponent, dragComponentPoint: Point): Boolean =
        dnd.headerAt(dragComponentPoint) != null

    override fun processMousePressed(event: MouseEvent) {
        pressedGroup = dnd.headerAt(RelativePoint(event).getPoint(dnd.tabs))?.groupId
    }

    override fun processDrag(event: MouseEvent, dragToScreenPoint: Point, startScreenPoint: Point) {
        if (isDragJustStarted) {
            if (!begin(startScreenPoint)) return
        }
        if (from < 0) return
        updateTarget(dragToScreenPoint)
        // centred on the pointer, like the platform's drag image (DockManagerImpl.setLocationFrom)
        val size = feedback.size
        feedback.moveGhost(Point(dragToScreenPoint.x - size.width / 2, dragToScreenPoint.y - size.height / 2), target?.dnd)
        event.consume()
    }

    override fun processDragFinish(event: MouseEvent, willDragOutStart: Boolean) {
        val t = target
        val group = pressedGroup
        val unitsAtStart = startUnits
        val index = from
        val flat = shownFlat
        val unchanged = flat == origFlat
        val ownTarget = t != null && t.dnd === dnd
        // the strip shows the result already for a drop in this split; for anything else it goes back first
        if (!ownTarget) restoreOwn()
        val groups = origGroups
        cleanup()
        if (t == null || group == null || index < 0) return
        try {
            if (t.dnd === dnd) {
                if (!unchanged) dnd.commitGroupOrder(groups, flat)
            }
            else {
                dnd.moveGroupToSplit(unitsAtStart[index], t.dnd, t.units, t.boundary)
            }
        }
        catch (e: Exception) {
            LOG.error("Moving group $group failed", e)
        }
    }

    override fun processDragCancel() {
        restoreOwn()
        cleanup()
    }

    private fun begin(startScreen: Point): Boolean {
        val group = pressedGroup ?: return false
        dnd.adapter.finishCollapseAnimations() // a drag starts from a settled strip (COLL-7)
        startUnits = dnd.buildUnits()
        from = startUnits.indexOfFirst { it.groupId == group }
        if (from < 0) return false
        minBoundary = DropRules.leadingPinned(startUnits)
        origGroups = dnd.model.groups()
        origFlat = dnd.reorder.completeOrder(startUnits.flatMap { it.keys })
        shownFlat = origFlat
        // The floating picture is the pill only (expanded or collapsed), in the style of the platform's tab picture; the gap in the strip
        // keeps the group's full size (pill and tabs).
        val strip = dnd.adapter.stripUnits()
        val pill = strip.filterIsInstance<StripUnit.Pill>().firstOrNull { it.groupId == group }
        val memberUnits = strip.filterIsInstance<StripUnit.Tab>().filter { it.groupId == group && !it.rect.isEmpty }
        groupFiles = memberUnits.map { it.file }
        val rect = HeaderDragGeometry.groupRect(pill?.rect, memberUnits.map { it.rect }) ?: return false
        val deco = origGroups.firstOrNull { it.id == group }
        var image = deco?.let { DragImages.group(dnd.tabs, it.name, dnd.adapter.tintColor(it.color)) }
        var size = image?.let { DragImages.sizeOf(it) }
        if (image == null || size == null) {
            val header = pill?.header?.takeIf { !it.bounds.isEmpty } ?: return false
            image = DragOverlay.snapshot(dnd.tabs, Rectangle(header.bounds), listOf(header to Rectangle(header.bounds)), DragImages.windowBackground())
            size = Dimension(header.width, header.height)
        }
        val unit = startUnits[from]
        val length = unit.lead.let { if (dnd.horizontal) it.width else it.height }
        this.size = DraggedGroupSize(
            horizontal = dnd.horizontal,
            length = length,
            pill = unit.pill?.let { if (dnd.horizontal) it.width else it.height } ?: length,
            collapsed = unit.collapsed,
            pillNatural = pill?.header?.pillLength() ?: 0,
            tabsNatural = dnd.naturalLength(groupFiles),
            tabCount = groupFiles.size,
        )
        LOG.info("group drag started: group=$group, rect=$rect, picture=$size")
        ownReach = dnd.stripScreenRect()
        feedback.begin(image, size)
        showOwnGap(true)
        return true
    }

    /** The placeholder for the group (its pill and tabs) in this split's strip, or removed. */
    private fun showOwnGap(on: Boolean) {
        val group = pressedGroup ?: return
        if (on == gapShown) return
        gapShown = on
        if (on) dnd.adapter.setDragGap(groupFiles, listOf(group)) else dnd.adapter.setDragGap(emptyList(), emptyList())
    }

    private fun updateTarget(screen: Point) {
        val own = dnd.stripScreenRect()
        if (own != null) ownReach = ownReach?.union(own) ?: Rectangle(own)
        val onOwn = own != null && own.contains(screen)
        // another split's strip wins over the sticky margin of this one, but not over this strip itself
        val other = if (onOwn) null
        else dnd.peers().firstOrNull { it !== dnd && it.stripScreenRect()?.contains(screen) == true }
            ?: foreign.claims(screen)
        val margin = JBUI.scale(ForeignGapSession.STICKY_MARGIN_PX)
        val nearOwn = !onOwn && other == null && ownReach?.let { StripDragGeometry.beside(it, dnd.horizontal, margin).contains(screen) } == true
        val resolved: Target? = when {
            onOwn || nearOwn -> {
                foreign.close()
                showOwnGap(true)
                val live = settleOwn(dnd.toTabs(screen))
                Target(dnd, live, shownBoundary(live), merge = false)
            }
            else -> {
                // the group leaves this strip: it goes back to where it was while the pointer is elsewhere
                restoreOwn()
                if (foreign.isElsewhere(other)) foreign.close()
                val groupSize = size
                if (other == null || groupSize == null) null
                else {
                    val otherUnits = other.buildUnits()
                    if (otherUnits.any { it.groupId == pressedGroup }) {
                        // that split has an instance of the group: the drop merges, the outline shows it (no gap)
                        foreign.close()
                        val p = other.toTabs(screen)
                        val b = DropRules.nearestBoundary(otherUnits.map { it.bounds }, other.horizontal, p, DropRules.leadingPinned(otherUnits))
                        Target(other, otherUnits, b, true)
                    }
                    else foreign.settle(other, screen, groupSize).let { Target(other, it.units, it.boundary, false) }
                }
            }
        }
        target = resolved
        showMarker(resolved)
    }

    /**
     * Moves the group's unit across the units it passes, live, until its slot settles against the pointer ([DropSlots.select] with
     * the group's unit as the placeholder, the same rule as for a tab). Returns the live units.
     */
    private fun settleOwn(pointer: Point): List<MoveUnit<VirtualFile>> {
        val group = pressedGroup ?: return emptyList()
        var units = dnd.buildUnits()
        repeat(4) {
            val at = units.indexOfFirst { it.groupId == group }
            if (at < 0) return units
            val others = units.filterIndexed { i, _ -> i != at }
            val cells = others.mapIndexed { i, u -> TabCell(i, u.groupId, u.pinned, Rectangle(u.bounds)) as StripCell<Int> }
            val unit = units[at]
            // the pill-sized leading part is what the pointer stands for; the group's whole first row is the room it takes
            val room = unit.lead.let { if (dnd.horizontal) it.width else it.height }
            val slot = DropSlots.select(cells, unit.pill ?: unit.bounds, at, pointer, dnd.horizontal, minBoundary.coerceAtMost(others.size), others.size, room)
            if (slot == at) return units
            val moved = ArrayList(others)
            moved.add(slot, units[at])
            val flat = dnd.reorder.completeOrder(moved.flatMap { it.keys })
            if (flat == shownFlat) return units
            dnd.reorder.reorder(origGroups, flat)
            shownFlat = flat
            if (!dnd.tabs.isValid) dnd.tabs.validate()
            units = dnd.buildUnits()
        }
        return units
    }

    private fun shownBoundary(units: List<MoveUnit<VirtualFile>>): Int = units.indexOfFirst { it.groupId == pressedGroup }.coerceAtLeast(0)

    /** The strip goes back to the order the drag began with, and the placeholder goes away. */
    private fun restoreOwn() {
        showOwnGap(false)
        if (shownFlat != origFlat && origFlat.isNotEmpty()) {
            dnd.reorder.reorder(origGroups, origFlat)
            shownFlat = origFlat
        }
    }

    private fun showMarker(t: Target?) {
        feedback.showMarker(t?.dnd, t?.units.orEmpty(), t?.merge ?: false, pressedGroup)
    }

    private fun cleanup() {
        foreign.close()
        size = null
        showOwnGap(false)
        feedback.clear()
        target = null
        ownReach = null
        from = -1
        startUnits = emptyList()
        origFlat = emptyList()
        shownFlat = emptyList()
        groupFiles = emptyList()
    }
}
