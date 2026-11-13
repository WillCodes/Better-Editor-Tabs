package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import dev.willcodes.bettereditortabs.tabgroups.model.strip.Landing
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripCell
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripUnit
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

private val LOG = logger<TabDragSession>()

/**
 * One tab drag on one strip, from the moment the pointer is over it to the drop, the move to another strip or the cancel
 * (DRAG-0..DRAG-7): the placeholder, the live re-order and previewed membership, the settle loop that picks the slot under the pointer,
 * the hold-to-join timer ([DragHold]), the restore of the original state and the drop result. The gesture (press, threshold, release,
 * Esc, hand-over to the platform) is [TabDragController]'s; which strip the pointer is over, and the floating picture across strips,
 * is [CrossSplitTabDrag]'s.
 *
 * The session runs on the [target] strip for the carried [dragged] tab, which comes from `dragged.source`. Three cases:
 * - **own strip** (`target === dragged.source`): the tab's place is the empty slot (`TabBarAdapter.setDragGap`), the strip is
 *   re-ordered live under it, membership is previewed (pill and tint) and only committed on release ([commit]);
 * - **another split's strip, tab not open there** (DRAG-5): the strip has no tab for it, so the slot is the foreign gap
 *   (`TabBarAdapter.setForeignGap`, sized like the tab in that strip's orientation by [ForeignGapSession.lengthIn], as for a group
 *   drag) that opens in front of the cell after the slot and moves with the pointer; nothing in the strip is re-ordered and no
 *   membership is previewed (there is no tab to tint), the picture is tinted instead. Dropping moves the file ([moveTabToSplit]);
 * - **another split's strip, file already open there**: as the own strip (that tab is the one that moves), with the picture made from
 *   the dragged tab's text and icon; dropping re-orders and re-groups it there and closes the source tab ([moveTabToSplit]).
 * [OwnGroupStay] (DRAG-3) only applies to the own strip: in another split the tab is no member of any group.
 *
 * A session can be [suspend]ed (strip back as it was, placeholder gone) and [resume]d, which the home session does while the pointer
 * is over another split's strip; a session for another strip is simply dropped ([restoreOriginal], [end]).
 *
 * Wraps `JBEditorTabs` (via [TabBarDnd]), `TabBarAdapter` drag APIs and the platform's drag picture ([DragImages]). EDT only.
 */
internal class TabDragSession(private val target: TabBarDnd, val dragged: DraggedTab, private val feedback: GroupDragFeedback) {
    /** What a drop leaves behind: the landing the strip shows and the state the drag began with. Passed to [commit]. */
    class Outcome(val applied: TabDropPlanner.Applied?, val groups: List<GroupDecoration>, val unchangedOrder: List<VirtualFile>)

    private val tabs get() = target.tabs
    private val adapter get() = target.adapter
    private val source get() = dragged.source
    private val file get() = dragged.file

    /** This session is on another split's strip. */
    val foreign: Boolean = target !== dragged.source

    /** The strip has a tab of [file] that moves with the drag (the own strip, or a split that has the file open already). */
    private var hasTab = true

    private var active = false
    private var origFlat: List<VirtualFile> = emptyList()
    private var origGroups: List<GroupDecoration> = emptyList()
    private var applied: TabDropPlanner.Applied? = null

    /** The order the strip shows now (re-ordering is skipped when the slot change does not change it). */
    private var shownFlat: List<VirtualFile> = emptyList()

    /** The slot the placeholder sits in now (index into the cells without the dragged tab). */
    private var slot = 0
    private var gapRoom = 0
    private var lastPointer: Point? = null
    private val hold = DragHold { lastPointer?.let { refresh(it) } }
    private val planner = TabDropPlanner(target, hold)

    /** The tab of [file] in the target strip, if the strip has one. */
    private fun tabInTarget(): TabInfo? = if (!foreign) dragged.info else StripReader.infoOf(tabs, file)

    /**
     * Starts the session on the strip with the pointer at [screen]: the placeholder and the first picture. False (and nothing changed) if
     * the strip cannot show it (the own tab is not laid out). [keepStay]: continue the own-group rule of an earlier session ([resume]).
     */
    fun begin(screen: Point, keepStay: Boolean = false): Boolean {
        if (!target.isShowing) return false
        adapter.finishCollapseAnimations() // a drag starts from a settled strip (COLL-7)
        target.validateNow()
        val own = tabInTarget()
        hasTab = own != null
        if (own?.isHidden == true) return false
        val strip = adapter.stripUnits()
        origGroups = target.model.groups()
        origFlat = target.reorder.completeOrder(TabDropPlanner.flatten(strip, origGroups))
        shownFlat = origFlat
        applied = null
        lastPointer = screen
        if (hasTab) {
            val at = strip.indexOfFirst { it is StripUnit.Tab && it.file == file }
            if (at < 0 || strip[at].rect.isEmpty) return false
            slot = at
            if (!keepStay) planner.start(if (foreign) null else origGroups.firstOrNull { file in it.members }?.id)
            adapter.beginTabDrag(file)
            adapter.setDragGap(listOf(file), emptyList())
        }
        else {
            gapRoom = gapLength() ?: return false
            if (!keepStay) planner.start(null)
            val cells = TabDropPlanner.cellsOf(strip, file)
            slot = planner.startSlot(cells, screen)
            openGap(cells, slot)
        }
        if (!snapshot()) {
            adapter.clearDragPreview()
            return false
        }
        active = true
        LOG.info("tab drag started on ${if (foreign) "another split's strip" else "its own strip"}: ${file.name}, slot=$slot, groups=${origGroups.map { it.id }}")
        return true
    }

    /** The own strip again after the pointer was over another split's strip. */
    fun resume(screen: Point): Boolean = begin(screen, keepStay = true)

    /** The strip goes back as it was and the session rests (the pointer is over another strip). */
    fun suspend() {
        restoreOriginal()
        stopTimers()
    }

    /** The room the tab takes in the target strip along its axis: its own length in a strip of the same orientation, else worked out there. */
    private fun gapLength(): Int? {
        val label = dragged.labelSize ?: return null
        val length = if (source.horizontal) label.width else label.height
        return ForeignGapSession.lengthIn(target, DraggedGroupSize(source.horizontal, length, label.width))
    }

    /** Opens the foreign gap in front of the cell that follows slot [at], and lays the strip out. */
    private fun openGap(cells: List<StripCell<VirtualFile>>, at: Int) {
        adapter.setForeignGap(TabDropPlanner.anchorFor(cells, at), gapRoom, dragged.pinned)
        target.validateNow()
    }

    /**
     * The floating picture: what the platform shows for a dragged tab, tinted while previewed into a group (DRAG-7). Made from the tab's
     * own strip, or, over another split's strip, from the tab's text and icon by that strip, with that strip's tint for the group the
     * tab would join. False if it could not be made.
     */
    private fun snapshot(): Boolean {
        val sourceTabs = source.tabs
        val image = if (foreign) {
            val tint = applied?.landing?.groupId?.let { adapter.tintFor(it) }
            DragImages.tab(tabs, dragged.info.text, dragged.info.icon, tint)
        }
        else DragImages.tab(sourceTabs, dragged.info)
        if (image != null) {
            feedback.replace(image, DragImages.sizeOf(image), false)
            return true
        }
        if (foreign) return feedback.size.width > 0 // keep the picture the other strip made
        // the platform's picture is not available: the tab's own label (it looks selected)
        val unit = source.adapter.stripUnits().filterIsInstance<StripUnit.Tab>().firstOrNull { it.file == file } ?: return false
        val label = sourceTabs.getTabLabel(dragged.info) ?: return false
        if (unit.rect.isEmpty) return false
        val rect = Rectangle(unit.rect)
        val fallback = DragOverlay.snapshot(sourceTabs, rect, listOf(label to Rectangle(label.bounds)), DragImages.windowBackground()) ?: return false
        feedback.replace(fallback, Dimension(rect.width, rect.height), false)
        return true
    }

    /** Moves the floating picture so that it is centred on the pointer, as the platform's drag image (`DockManagerImpl.setLocationFrom`). */
    fun moveGhost(screen: Point) {
        lastPointer = screen
        val size = feedback.size
        feedback.moveGhost(Point(screen.x - size.width / 2, screen.y - size.height / 2), target)
    }

    /** The pointer moved to [screen] (while it is on the strip): the slot follows it. */
    fun pointerMoved(screen: Point) {
        refresh(screen)
    }

    /**
     * Works out the slot under the pointer and applies it to the strip if it changed. A change re-lays the strip out, which can move
     * the placeholder (another row, a pill re-hosted); the pointer is judged again against the new layout until the slot settles
     * (at most [SETTLE_STEPS] times), so one mouse event ends with the placeholder where the pointer is.
     */
    private fun refresh(screen: Point) {
        if (!active) return
        repeat(SETTLE_STEPS) { if (!step(screen)) return }
    }

    /** One evaluation; true if it changed the strip. */
    private fun step(screen: Point): Boolean {
        target.validateNow()
        val strip = adapter.stripUnits()
        val cells = TabDropPlanner.cellsOf(strip, file)
        val gap = if (hasTab) strip.filterIsInstance<StripUnit.Tab>().firstOrNull { it.file == file }?.rect else adapter.foreignGapRect()
        val result = planner.landingFor(gap, cells, dragged.pinned, slot, screen)
        val moved = result.slot != slot
        slot = result.slot
        val before = applied
        if (before?.landing == result) return false
        if (!hasTab && moved) openGap(cells, slot)
        apply(cells, result, groupChanged = before == null || before.landing.groupId != result.groupId)
        return true
    }

    private fun apply(cells: List<StripCell<VirtualFile>>, landing: Landing, groupChanged: Boolean) {
        val flat = target.reorder.completeOrder(TabDropPlanner.flatOrder(cells, landing, file, origGroups))
        if (hasTab) {
            if (flat != shownFlat) {
                target.reorder.reorder(origGroups, flat)
                shownFlat = flat
            }
            // after the groups were re-applied: the preview (pill placement and tint) follows the previewed membership
            adapter.setDragPreview(file, landing.groupId)
        }
        applied = TabDropPlanner.Applied(landing, flat)
        if (groupChanged) {
            target.validateNow()
            snapshot() // the picture carries the live tint (DRAG-7)
        }
    }

    /**
     * The release: judges the release position itself, not only the last drag event, and ends the session (the hold timer goes away; the
     * strip already shows the result). The caller then settles the gesture's own state and calls [commit] (own strip) or moves the
     * file ([moveTabToSplit]).
     */
    fun finishDrop(screen: Point): Outcome {
        refresh(screen)
        val outcome = Outcome(applied, origGroups, origFlat)
        end()
        return outcome
    }

    /** The preview is removed and the model learns about the drop (a group left without a member is removed by the model, MEM-5). */
    fun commit(outcome: Outcome) {
        adapter.clearDragPreview()
        val a = outcome.applied ?: return
        val groups = outcome.groups
        val newGroups = TabDropPlanner.groupsAfterDrop(groups, file, a)
        LOG.info("tab drop: ${file.name} -> ${a.landing.groupId ?: "ungrouped"} at slot ${a.landing.slot}, order changed: ${a.flat != outcome.unchangedOrder}")
        if (newGroups == groups && a.flat == outcome.unchangedOrder) {
            adapter.setGroups(groups)
            return
        }
        target.model.commit(newGroups)
    }

    /** The target strip's placeholder and previews go away (the strip keeps the order it shows). */
    fun clearPreview() {
        adapter.clearDragPreview()
    }

    /** Back to the order and membership the drag started with, preview and gap removed. */
    fun restoreOriginal() {
        adapter.clearDragPreview()
        if (shownFlat != origFlat) {
            target.reorder.reorder(origGroups, origFlat)
            shownFlat = origFlat
        }
        applied = null
    }

    /** Stops the hold timer (the strip is going away, or the session ends). */
    fun stopTimers() {
        active = false
        hold.reset()
    }

    /** Ends the session: the hold timer goes away. Does not touch the strip or the picture. */
    fun end() {
        stopTimers()
        applied = null
        lastPointer = null
    }

    companion object {
        /** Can a session run on [dnd] for [file]? Not if the strip is not showing, or the file is open there in a tab that is not showing. */
        fun accepts(dnd: TabBarDnd, file: VirtualFile): Boolean {
            if (dnd.stripScreenRect() == null) return false
            val own = StripReader.infoOf(dnd.tabs, file)
            return own == null || !own.isHidden
        }

        /** How often one mouse event may re-evaluate the slot after the previous evaluation changed the layout. */
        private const val SETTLE_STEPS = 4
    }
}
