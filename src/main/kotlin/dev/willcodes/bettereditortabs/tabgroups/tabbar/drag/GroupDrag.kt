package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.model.strip.DropRules
import dev.willcodes.bettereditortabs.tabgroups.model.strip.DropSlots
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EditorSide
import dev.willcodes.bettereditortabs.tabgroups.model.strip.GroupDropRules
import dev.willcodes.bettereditortabs.tabgroups.model.strip.GroupLanding
import dev.willcodes.bettereditortabs.tabgroups.model.strip.HeaderDragGeometry
import dev.willcodes.bettereditortabs.tabgroups.model.strip.MoveUnit
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripCell
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripDragGeometry
import dev.willcodes.bettereditortabs.tabgroups.model.strip.TabCell
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripUnit
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

private val LOG = logger<GroupDrag>()

/**
 * One drag of a whole group (INT-6, SPLIT-5), from the first move to the drop or Esc; the gesture (press, dead zone, release, Esc) is
 * [HeaderDragHelper]'s.
 *
 * **The group is always its collapsed header while it is dragged (INT-6, user request 2026-11-13).** An expanded group is collapsed in
 * its strip when the drag begins (instantly: COLL-7, a drag never animates), so its tabs are out of the strip as for a collapsed group
 * and the placeholder in the strip, and the gap in another split's strip, are the size of a pill. The model is not touched (the host
 * still says expanded: [origGroups]); the strip shows [dragGroups]. COLL-2 applies to the collapse: if the selected tab is in the
 * group, the selection moves to the nearest tab outside it, and **is put back** when the drag ends (Esc, a drop in place, a drop
 * elsewhere in this strip, and a move to another split, where the moved selected tab is selected there as before), so a group drag
 * never changes which tab is the current one. (IntelliJ's own drag of a tab selects another tab while the tab is in transit too.) The
 * strip's own change counting ([dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.TabBarAdapter.ownChange]) keeps COLL-4 from
 * reporting any of it. When the drag ends the group comes back expanded with the expand animation (COLL-7, [expanding]), and only it
 * animates.
 *
 * Looks like a tab drag: one picture of the group (a chip with its pill and tab count, [GroupChipImage]) floats centred on the pointer,
 * and in this split's strip the group's place is an empty slot of the pill's size that moves live between the other units while the strip
 * slides around it ([DropSlots.select]). Targets, from the pointer position:
 * - this split's strip: the slot above, never inside a group; dropping commits the order;
 * - another split's strip: a pill-sized gap opens there and follows the pointer ([ForeignGapSession]), or, if that split already has an
 *   instance of the group, that instance is outlined (the drop merges);
 * - the edge of an editor area, of any split (also this one) or window: the platform's drop-area highlight shows the half that becomes
 *   a new split, and the drop makes it with the group in it ([moveGroupToNewSplit]); the middle of another split's area: the group
 *   moves into that split ([moveGroupToSplit], appended, merged if it has the group); the middle of this split's own area: no target;
 * - outside every editor area (and strip), also outside the IDE window: no highlight, the drop makes a detached editor window around the
 *   pointer ([moveGroupToNewWindow]);
 * - the divider between splits: no target.
 * With Ctrl held at the release (the platform's copy modifier for a tab) every target outside this split's strip gets a copy: the
 * files open there as a linked instance of the group (SPLIT-2) and the group stays in this split as it was.
 * After a move or copy the split that received the group is the current one ([focusArrival]).
 * The choice is pure ([GroupDropRules]: strip > edge > centre > new window); the platform's own geometry decides the side
 * ([dropSideAt], `TabsUtil.getDropSideFor`) and the highlight ([dropAreaScreen]).
 *
 * **While the pointer is not over (or beside) this split's strip, the group is out of this strip** ([StripDragSurface.setGroupTransit], as a
 * dragged tab is while its drag is over another split, [TabTransit]): no pill, no reserved space, no placeholder, the neighbours close up,
 * and over this strip again the pill-sized placeholder comes back where the pointer is. A drop, Esc or a lost drag ends it before the group
 * comes back (expanded, animated) or moves; the model is untouched until the drop.
 * A strip that is longer than its window scrolls while the pointer is in its edge zone or by the mouse wheel ([DragAutoScroll]).
 *
 * Wraps [TabBarDnd] and its adapter ([dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.TabBarAdapter.setGroups], `setDragGap`). EDT only.
 */
internal class GroupDrag(private val dnd: TabBarDnd, private val group: String) {
    /** What a release would do now. */
    private sealed class Target {
        /** The split whose frame the pointer is over (draws the picture), null where there is none. */
        abstract val over: TabBarDnd?

        /** A tab strip: this split's own (the live order) or another split's (gap or merge outline). */
        class Strip(val dnd: TabBarDnd, val units: List<MoveUnit<VirtualFile>>, val boundary: Int, val merge: Boolean) : Target() {
            override val over: TabBarDnd get() = dnd
        }

        /** The edge [side] of the editor area of [dnd]: a new split. */
        class SplitEdge(val dnd: TabBarDnd, val side: EditorSide) : Target() {
            override val over: TabBarDnd get() = dnd
        }

        /** The middle of another split's editor area: the group joins that split. */
        class IntoSplit(val dnd: TabBarDnd) : Target() {
            override val over: TabBarDnd get() = dnd
        }

        /** Outside every editor area: a detached window. */
        object NewWindow : Target() {
            override val over: TabBarDnd? get() = null
        }
    }

    /** The units as they were when the drag began (the live strip is re-ordered while the group is dragged inside this split). */
    private var startUnits: List<MoveUnit<VirtualFile>> = emptyList()
    private var from = -1
    private var minBoundary = 0
    private var target: Target? = null

    /** Where the pointer was last (the release point: a new window is centred on it). */
    private var lastPointer = Point()
    private val feedback = GroupDragFeedback(dnd)

    /** Scrolls the strip under the pointer near its ends, and by the mouse wheel. */
    internal val autoScroll = DragAutoScroll { screen -> if (from >= 0) follow(screen) }

    /** The groups as the host has them: what a drop commits. */
    private var origGroups: List<GroupDecoration> = emptyList()

    /** What the strip shows during the drag: [origGroups] with the dragged group collapsed. */
    private var dragGroups: List<GroupDecoration> = emptyList()

    /** The group was expanded when the drag began: it is collapsed for the drag and comes back expanded and animated. */
    private var wasExpanded = false

    /** The selected file when the drag began (put back when it ends). */
    private var selectedBefore: VirtualFile? = null
    private var origFlat: List<VirtualFile> = emptyList()
    private var shownFlat: List<VirtualFile> = emptyList()

    /** The live-moving placeholder is shown in this split's strip (the pointer is over it). */
    private var gapShown = false

    /** The size of the dragged group's header as it was when the drag began; null outside a drag. */
    private var size: DraggedGroupSize? = null

    /** The group is out of this split's strip (the pointer is over another split's strip or none): no pill, no space, no placeholder here. */
    private var inTransit = false

    /** The gap opened for the group in another split's strip. */
    private val foreign = ForeignGapSession()

    /**
     * Everything this strip covered on screen at any moment of this drag. Moving the group changes the number of rows of a wrapped
     * strip, so the live strip rectangle shrinks and grows because of the drag itself; "has the pointer left this strip" is judged
     * against the widest it has been (plus [ForeignGapSession.STICKY_MARGIN_PX] across the strip), which cannot flip back and forth.
     */
    private var ownReach: Rectangle? = null

    /** True once [begin] succeeded and until [finish] / [cancel]. */
    val isRunning: Boolean get() = from >= 0

    /** The group animates open again when the drag ends (it was expanded before). */
    private val expanding: String? get() = if (wasExpanded) group else null

    /** Starts the drag. False (nothing changed) if the group cannot be dragged (no picture, not in the strip). */
    fun begin(): Boolean {
        dnd.adapter.finishCollapseAnimations() // a drag starts from a settled strip (COLL-7)
        origGroups = dnd.model.groups()
        val deco = origGroups.firstOrNull { it.id == group } ?: return false
        // The floating picture is a chip with the group's pill and its tab count (INT-6; expanded or collapsed); it is made while the
        // group still shows its tabs. The count comes from the model: a collapsed group's tabs are hidden.
        val strip = dnd.adapter.stripUnits()
        val pill = strip.filterIsInstance<StripUnit.Pill>().firstOrNull { it.groupId == group }
        val memberRects = strip.filterIsInstance<StripUnit.Tab>().filter { it.groupId == group && !it.rect.isEmpty }.map { it.rect }
        val rect = HeaderDragGeometry.groupRect(pill?.rect, memberRects) ?: return false
        var image = DragImages.groupChip(dnd.tabs, deco, deco.members.size, dnd.adapter.currentStyle())
        var pictureSize = image?.let { DragImages.sizeOf(it) }
        if (image == null || pictureSize == null) {
            val header = pill?.header?.takeIf { !it.bounds.isEmpty } ?: return false
            image = DragOverlay.snapshot(dnd.tabs, Rectangle(header.bounds), listOf(header to Rectangle(header.bounds)), DragImages.windowBackground())
            pictureSize = Dimension(header.width, header.height)
        }

        // The group is its collapsed header for the drag: an expanded group is collapsed in the strip (not in the model).
        wasExpanded = !deco.collapsed
        selectedBefore = dnd.adapter.selectedFile()
        dragGroups = origGroups.map { if (it.id == group) it.copy(collapsed = true) else it }
        if (wasExpanded) {
            // only the pill and the tabs leave the strip; the editor keeps showing even if the group was all of the split
            dnd.adapter.keepEditorDuringDrag(true)
            dnd.adapter.setGroups(dragGroups)
            dnd.validateNow()
        }
        startUnits = dnd.buildUnits()
        from = startUnits.indexOfFirst { it.groupId == group }
        if (from < 0) {
            undoCollapse()
            return false
        }
        minBoundary = DropRules.leadingPinned(startUnits)
        origFlat = dnd.reorder.completeOrder(startUnits.flatMap { it.keys })
        shownFlat = origFlat
        val unit = startUnits[from]
        // the pill's whole length, not the part on screen: a pill cut at the end of a scrolled strip opens a full-size gap elsewhere
        // (user report 2026-11-13); in a side strip the pill is a whole row, which is never cut along the strip
        val natural = pill?.header?.pillLength()
        val length = if (dnd.horizontal) natural ?: unit.lead.width else unit.lead.height
        size = DraggedGroupSize(dnd.horizontal, length, natural ?: length)
        LOG.info("group drag started: group=$group, expanded=$wasExpanded, rect=$rect, picture=$pictureSize")
        ownReach = dnd.stripScreenRect()
        feedback.begin(image, pictureSize)
        showOwnGap(true)
        return true
    }

    /** The pointer is at [screen]: the target follows, the strip under it may scroll, and the picture goes to it (centred, like the platform's). */
    fun move(screen: Point) {
        if (from < 0) return
        lastPointer = Point(screen)
        follow(screen)
        val picture = feedback.size
        feedback.moveGhost(Point(screen.x - picture.width / 2, screen.y - picture.height / 2), target?.over)
    }

    /** The pointer is at [screen] (it moved, or the strip under it scrolled): the target follows, and the strip under it may scroll. */
    private fun follow(screen: Point) {
        updateTarget(screen)
        autoScroll.update((target as? Target.Strip)?.dnd, screen)
    }

    /** The release: the group is dropped where the live strip shows it, in another split's strip, or nowhere (it goes back). */
    fun finish(copy: Boolean = false) {
        autoScroll.stop()
        val t = target
        val unitsAtStart = startUnits
        val index = from
        val flat = shownFlat
        val unchanged = flat == origFlat
        val ownTarget = t is Target.Strip && t.dnd === dnd
        // A move to another split: both strips are frozen as they look now (drop gap open) before anything below changes them. A move
        // to a split that does not exist yet has only this strip to freeze.
        val moving = t != null && !ownTarget && index >= 0
        val freezes = when {
            !moving -> emptyList()
            t is Target.Strip -> dnd.freezeStrips(t.dnd)
            t is Target.IntoSplit -> dnd.freezeStrips(t.dnd)
            else -> dnd.freezeOwn()
        }
        // the strip shows the result already for a drop in this split; for anything else it goes back first
        if (!ownTarget) restoreOwn()
        val groups = origGroups
        val selected = selectedBefore
        val expand = expanding
        val release = Point(lastPointer)
        cleanup()
        if (t == null || index < 0) {
            backExpanded()
            releaseLater(freezes)
            return
        }
        try {
            val unit = unitsAtStart[index]
            when (t) {
                is Target.Strip -> if (t.dnd === dnd) {
                    if (unchanged) backExpanded() else dnd.commitGroupOrder(groups, flat, expand)
                    dnd.adapter.reselect(selected)
                }
                else {
                    dnd.moveGroupToSplit(unit, t.dnd, t.units, t.boundary, freezes, selected, expand, copy)
                    if (copy) backInSource()
                }
                is Target.IntoSplit -> {
                    // INT-6: the middle of another split's area is a drop on the split, not at a slot: merged, or appended at the end
                    val units = t.dnd.buildUnits()
                    dnd.moveGroupToSplit(unit, t.dnd, units, units.size, freezes, selected, expand, copy)
                    if (copy) backInSource()
                }
                is Target.SplitEdge ->
                    if (!dnd.moveGroupToNewSplit(unit, t.dnd, t.side, freezes, selected, expand, copy)) notMoved(freezes)
                    else if (copy) backInSource()
                Target.NewWindow ->
                    if (!dnd.moveGroupToNewWindow(unit, release, freezes, selected, expand, copy)) notMoved(freezes)
                    else if (copy) backInSource()
            }
        }
        catch (e: Exception) {
            LOG.error("Moving group $group failed", e)
            releaseLater(freezes)
        }
    }

    /**
     * A copy (Ctrl held at the release, the platform's copy modifier for a tab): the target got a linked instance of the group
     * (SPLIT-2), and the group comes back in this split as it was, expanded again if it was, without taking the focus from the target.
     */
    private fun backInSource() {
        if (wasExpanded) dnd.adapter.expandDropped(origGroups, group)
        dnd.adapter.reselect(selectedBefore) // without focus: the target stays the current split
    }

    /** The group could not be moved (no split or window was made): it stays where it was, expanded again. */
    private fun notMoved(freezes: List<StripFreeze>) {
        backExpanded()
        releaseLater(freezes)
    }

    /** Esc (or the drag was lost): everything goes back, the group expanded again. */
    fun cancel() {
        autoScroll.stop()
        restoreOwn()
        cleanup()
        backExpanded()
    }

    /** Puts the group back expanded in this strip with the expand animation, and the selection back (nothing was committed). */
    private fun backExpanded() {
        if (wasExpanded) dnd.adapter.expandDropped(origGroups, group)
        dnd.adapter.reselect(selectedBefore)
    }

    /** The collapse of [begin] is taken back at once (the drag could not start). */
    private fun undoCollapse() {
        dnd.adapter.keepEditorDuringDrag(false)
        if (wasExpanded) dnd.adapter.setGroups(origGroups)
        dnd.adapter.reselect(selectedBefore)
    }

    /** The placeholder for the group (its pill) in this split's strip, or removed. */
    private fun showOwnGap(on: Boolean) {
        if (on == gapShown) return
        gapShown = on
        if (on) dnd.adapter.drag.setDragGap(emptyList(), listOf(group)) else dnd.adapter.drag.setDragGap(emptyList(), emptyList())
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
                setTransit(false)
                showOwnGap(true)
                val live = settleOwn(dnd.toTabs(screen))
                Target.Strip(dnd, live, shownBoundary(live), merge = false)
            }
            else -> {
                // the group leaves this strip: it goes back to where it was while the pointer is elsewhere
                restoreOwn()
                setTransit(true)
                if (foreign.isElsewhere(other)) foreign.close()
                val groupSize = size
                if (other == null) editorTarget(screen)
                else if (groupSize == null) null
                else {
                    val otherUnits = other.buildUnits()
                    if (otherUnits.any { it.groupId == group }) {
                        // that split has an instance of the group: the drop merges, the outline shows it (no gap)
                        foreign.close()
                        val p = other.toTabs(screen)
                        val b = DropRules.nearestBoundary(otherUnits.map { it.bounds }, other.horizontal, p, DropRules.leadingPinned(otherUnits))
                        Target.Strip(other, otherUnits, b, true)
                    }
                    else foreign.settle(other, screen, groupSize).let { Target.Strip(other, it.units, it.boundary, false) }
                }
            }
        }
        target = resolved
        val strip = resolved as? Target.Strip
        feedback.showMarker(strip?.dnd, strip?.units.orEmpty(), strip?.merge ?: false, group)
        showDropArea(resolved)
    }

    /**
     * The pointer is over no strip: the edge or the middle of an editor area, the divider between splits, or outside every editor
     * (INT-6). The decision is [GroupDropRules.resolve].
     */
    private fun editorTarget(screen: Point): Target? {
        val hit = dnd.editorAreaAt(screen)
        val landing = GroupDropRules.resolve(
            overStrip = false, side = hit?.side, ownSplit = hit?.dnd === dnd, overEditorRegion = hit == null && dnd.overEditorRegion(screen),
        )
        return when (landing) {
            GroupLanding.SPLIT_EDGE -> Target.SplitEdge(hit!!.dnd, hit.side)
            GroupLanding.INTO_SPLIT -> Target.IntoSplit(hit!!.dnd)
            GroupLanding.NEW_WINDOW -> Target.NewWindow
            GroupLanding.STRIP, GroupLanding.NONE -> null
        }
    }

    /** The platform's drop-area highlight for [t] (the half that becomes a split, the whole area for a move into a split); nothing for the other targets. */
    private fun showDropArea(t: Target?) {
        when (t) {
            is Target.SplitEdge -> feedback.showDropArea(t.dnd, t.dnd.dropAreaScreen(t.side))
            is Target.IntoSplit -> feedback.showDropArea(t.dnd, t.dnd.dropAreaScreen(EditorSide.CENTER))
            else -> feedback.showDropArea(null, null)
        }
    }

    /**
     * Moves the group's unit across the units it passes, live, until its slot settles against the pointer ([DropSlots.select] with
     * the group's unit as the placeholder, the same rule as for a tab; its room is the pill's length). Returns the live units.
     */
    private fun settleOwn(pointer: Point): List<MoveUnit<VirtualFile>> {
        var units = dnd.buildUnits()
        repeat(4) {
            val at = units.indexOfFirst { it.groupId == group }
            if (at < 0) return units
            val others = units.filterIndexed { i, _ -> i != at }
            val cells = others.mapIndexed { i, u -> TabCell(i, u.groupId, u.pinned, Rectangle(u.bounds)) as StripCell<Int> }
            val unit = units[at]
            val room = unit.lead.let { if (dnd.horizontal) it.width else it.height }
            val slot = DropSlots.select(cells, unit.pill ?: unit.bounds, at, pointer, dnd.horizontal, minBoundary.coerceAtMost(others.size), others.size, room)
            if (slot == at) return units
            val moved = ArrayList(others)
            moved.add(slot, units[at])
            val flat = dnd.reorder.completeOrder(moved.flatMap { it.keys })
            if (flat == shownFlat) return units
            dnd.reorder.reorder(dragGroups, flat)
            shownFlat = flat
            dnd.validateNow()
            units = dnd.buildUnits()
        }
        return units
    }

    private fun shownBoundary(units: List<MoveUnit<VirtualFile>>): Int = units.indexOfFirst { it.groupId == group }.coerceAtLeast(0)

    /** The group leaves this strip ([out]) or comes back into it, where its pill is laid out again before the placeholder is shown. */
    private fun setTransit(out: Boolean) {
        if (out == inTransit) return
        inTransit = out
        dnd.adapter.drag.setGroupTransit(if (out) group else null)
        if (!out) dnd.validateNow()
    }

    /** The strip goes back to the order the drag began with, and the placeholder goes away. */
    private fun restoreOwn() {
        showOwnGap(false)
        if (shownFlat != origFlat && origFlat.isNotEmpty()) {
            dnd.reorder.reorder(dragGroups, origFlat)
            shownFlat = origFlat
        }
    }

    private fun cleanup() {
        dnd.adapter.keepEditorDuringDrag(false)
        autoScroll.release()
        foreign.close()
        setTransit(false)
        size = null
        showOwnGap(false)
        feedback.clear()
        target = null
        ownReach = null
        from = -1
        startUnits = emptyList()
        origFlat = emptyList()
        shownFlat = emptyList()
    }

    /** The strip is going away: only timers and the picture are released. */
    fun dispose() {
        autoScroll.release()
        feedback.clear()
    }
}
