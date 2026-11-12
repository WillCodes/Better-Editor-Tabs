package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.InplaceButton
import com.intellij.ui.MouseDragHelper
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsUtil
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.model.strip.Landing
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripCell
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripDragGeometry
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripUnit
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.TabBarAdapter
import java.awt.Dimension
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

private val LOG = logger<TabDragController>()

/**
 * Runs tab drag and drop inside one tab bar itself (DRAG-0..DRAG-7), instead of letting the platform's `DragHelper` swap labels.
 *
 * Why: `DragHelper` only swaps the dragged label with other *labels*. A group pill is not a label (its space is borrowed inside a
 * neighbouring label), so the platform could never drop a tab between a pill and its neighbours. Here the strip is a sequence of
 * cells (tabs and pills, [TabBarAdapter.stripUnits]) and the pointer picks a slot between two cells ([DropSlots.select]); the
 * pill is a cell of its own with its own hit area.
 *
 * What the user sees is what the platform shows for a tab drag in the New UI (the drag-out phase of `DragHelper`, i.e. the drop
 * session of `DockManagerImpl`): the dragged tab's place in the strip is an empty slot ([TabBarAdapter.setDragGap], the look of
 * `TabLabel.paint` for a drop target, `TabLabel.kt:321-339`) and one picture of the tab floats centred on the pointer
 * (`DockManagerImpl.kt:236-243`, 90 % opaque, [DragOverlay]); there is no second copy of the tab in the strip. The picture is the
 * tab's own label, painted again whenever the previewed membership changes, so it carries the live tint (DRAG-7); the slot stays
 * neutral like the platform's.
 *
 * Gesture: a left press on a tab label (not on a pill, not on a close button) is only remembered; nothing is consumed, the tab is
 * selected as usual and clicks, middle-click and the context menu work. Once the pointer moved past the platform's drag threshold
 * the drag is ours: [DragObserver] consumes the drag events so `DragHelper` never starts.
 * - In the strip: whenever the slot (or the group the tab would join) changes the strip is re-ordered live with
 *   [TabBarAdapter.applyOrder] and [TabBarAdapter.setDragPreview] shows the previewed pill and tint (DRAG-7). The slot is chosen by
 *   [DropSlots.select] against the live layout (placeholder at the current slot), re-evaluated until it settles, which is what keeps
 *   it from flipping back and forth. Membership is only committed to the model on release ([SplitGroups.commit]). Esc restores
 *   order and membership.
 * - Pointer more than [HAND_OFF_MARGIN_PX] beyond the strip across its axis (the platform's own drop tolerance):
 *   the original order and membership are restored and the gesture is handed to the platform's drag-out ([DragHandOff]); from then
 *   on it is exactly the platform drag. If the pointer comes back onto the strip the platform's session is cancelled and the drag
 *   continues here, so a release over the strip always commits in the strip (the platform would turn a release over a pill into a
 *   split: `getDropIndexFor` is -1 there).
 *
 * Not handled here: dropping on another split's strip is done by the platform after the hand-over (the tab then arrives as a new
 * tab and MEM-2 keeps it out of the middle of a group); auto-scroll while dragging (the platform's `DragHelper` has none either:
 * the dragged tab is the selected one, and the layout scrolls the selection into view when it is re-ordered).
 *
 * Internal APIs wrapped: `JBTabsImpl` (tab labels, `TabInfo.dragOutDelegate`, `DragHelper` press state). EDT only.
 */
internal class TabDragController(private val dnd: TabBarDnd) {
    private enum class Phase { IDLE, PRESSED, DRAGGING, HANDED_OVER, CANCELLED }

    private var phase = Phase.IDLE
    private val tabs get() = dnd.tabs
    private val adapter get() = dnd.adapter

    private var file: VirtualFile? = null
    private var info: TabInfo? = null
    private var pressScreen = Point()
    private var ghostSize = Dimension()

    private var origFlat: List<VirtualFile> = emptyList()
    private var origGroups: List<GroupDecoration> = emptyList()
    private var applied: TabDropPlanner.Applied? = null

    /** The order the strip shows now (re-ordering is skipped when the slot change does not change it). */
    private var shownFlat: List<VirtualFile> = emptyList()

    /** The slot the placeholder sits in now (index into the cells without the dragged tab). */
    private var slot = 0
    private var handOff: DragHandOff? = null
    private var handOverBand: IntRange? = null

    /** The widest band the strip had during this drag ([StripDragGeometry.widen]): leaving the strip is judged against it. */
    private var reach: IntRange? = null
    private var lastPointer: Point? = null
    private var dispatcherAdded = false
    private val hold = DragHold { lastPointer?.let { refresh(it) } }
    private val planner = TabDropPlanner(dnd, hold)

    private val escape = KeyEventDispatcher { e ->
        if (e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE && isActive) {
            cancel()
            true
        }
        else false
    }

    /** True while the drag is ours (in the strip or handed over). */
    val isActive: Boolean get() = phase == Phase.DRAGGING || phase == Phase.HANDED_OVER

    // ---- press ---------------------------------------------------------------------------------------------------------

    fun mousePressed(e: MouseEvent) {
        if (phase != Phase.IDLE) abort()
        if (e.button != MouseEvent.BUTTON1 || e.isConsumed || !MouseDragHelper.checkModifiers(e)) return
        // Sorted alphabetically, the platform re-sorts the strip itself and turns sorting off after a manual drag: leave it alone.
        if (UISettings.getInstance().sortTabsAlphabetically) return
        val screen = RelativePoint(e).screenPoint
        val p = dnd.toTabs(screen)
        if (!tabs.isShowing || dnd.headerAt(p) != null) return
        if (tabs.findComponentAt(p) is InplaceButton) return
        val f = dnd.tabFileAt(p) ?: return
        val i = tabs.findInfo(f) ?: return
        file = f
        info = i
        pressScreen = Point(screen)
        phase = Phase.PRESSED
    }

    // ---- drag ----------------------------------------------------------------------------------------------------------

    /** Returns true if the event was taken (the caller consumes it). */
    fun mouseDragged(e: MouseEvent): Boolean {
        when (phase) {
            Phase.IDLE -> return false
            Phase.PRESSED -> {
                if (e.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK == 0) {
                    phase = Phase.IDLE
                    return false
                }
                val screen = RelativePoint(e).screenPoint
                // one pixel under the platform's own dead zone, so that this always wins the race against DragHelper
                val threshold = maxOf(1, JBUI.scale(MouseDragHelper.DRAG_START_DEADZONE) - 1)
                if (!StripDragGeometry.pastThreshold(pressScreen, screen, threshold)) return false
                if (!begin()) {
                    phase = Phase.IDLE
                    return false
                }
                update(e)
                return true
            }
            Phase.DRAGGING -> {
                update(e)
                return true
            }
            Phase.HANDED_OVER -> {
                if (isBackOnStrip(Point(RelativePoint(e).screenPoint))) handBack(e) else handOff?.process(e)
                return true
            }
            Phase.CANCELLED -> return true
        }
    }

    /** Starts (or, after a hand-back, restarts) the drag in the strip: placeholder, floating picture, Esc handler. */
    private fun begin(): Boolean {
        val f = file ?: return false
        val i = info ?: return false
        if (!tabs.isShowing || i.isHidden) return false
        adapter.finishCollapseAnimations() // a drag starts from a settled strip (COLL-7)
        if (!tabs.isValid) tabs.validate()
        val strip = adapter.stripUnits()
        val at = strip.indexOfFirst { it is StripUnit.Tab && it.file == f }
        if (at < 0 || strip[at].rect.isEmpty) return false
        origGroups = dnd.model.groups()
        origFlat = dnd.reorder.completeOrder(planner.flatten(strip, origGroups))
        shownFlat = origFlat
        applied = null
        reach = null
        slot = at
        adapter.beginTabDrag(f)
        adapter.setDragGap(listOf(f), emptyList())
        if (!snapshot()) {
            adapter.clearDragPreview()
            return false
        }
        if (!dispatcherAdded) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escape)
            dispatcherAdded = true
        }
        phase = Phase.DRAGGING
        LOG.info("tab drag started: ${f.name}, slot=$slot, groups=${origGroups.map { it.id }}")
        return true
    }

    /** The floating picture: what the platform shows for a dragged tab, tinted while previewed into a group (DRAG-7). False if it is not laid out. */
    private fun snapshot(): Boolean {
        val f = file ?: return false
        val i = info ?: return false
        val unit = adapter.stripUnits().filterIsInstance<StripUnit.Tab>().firstOrNull { it.file == f } ?: return false
        if (unit.rect.isEmpty) return false
        val image = DragImages.tab(tabs, i)
        if (image != null) {
            ghostSize = DragImages.sizeOf(image)
            dnd.overlay.setGhost(image, ghostSize)
            return true
        }
        // the platform's picture is not available: the tab's own label (it looks selected)
        val label = tabs.getTabLabel(i) ?: return false
        val rect = Rectangle(unit.rect)
        val fallback = DragOverlay.snapshot(tabs, rect, listOf(label to Rectangle(label.bounds)), DragImages.windowBackground())
        ghostSize = Dimension(rect.width, rect.height)
        dnd.overlay.setGhost(fallback, ghostSize)
        return true
    }

    private fun update(e: MouseEvent) {
        val screen = Point(RelativePoint(e).screenPoint)
        lastPointer = screen
        if (!tabs.isShowing) {
            cancel()
            return
        }
        // centred on the pointer, as the platform's drag image (DockManagerImpl.setLocationFrom)
        dnd.overlay.moveGhost(Point(screen.x - ghostSize.width / 2, screen.y - ghostSize.height / 2))
        val band = StripDragGeometry.widen(reach, stripBand())
        reach = band
        if (StripDragGeometry.isOutsideBand(band, dnd.toTabs(screen), dnd.horizontal, JBUI.scale(HAND_OFF_MARGIN_PX))
            && DragHandOff(info ?: return).available
        ) {
            handOverBand = band
            handOver(e)
            return
        }
        refresh(screen)
    }

    /** The strip's extent across its axis (tab-bar coordinates): the platform's header rectangle and everything that is on screen. */
    private fun stripBand(): IntRange? {
        val rects = ArrayList<Rectangle>()
        tabs.lastLayoutPass?.headerRectangle?.let { rects += Rectangle(it) }
        for (u in adapter.stripUnits()) rects += u.rect
        return StripDragGeometry.crossBand(rects, dnd.horizontal)
    }

    /**
     * After a hand-over: is the pointer on the strip again? Judged against the band the strip had when the drag left it (the source
     * tab is hidden by the platform now, a one-tab strip would have no band left), with no tolerance, so that this cannot flap at
     * the hand-over border: the tolerance region between the strip and the hand-over point belongs to the platform's drag.
     */
    private fun isBackOnStrip(screen: Point): Boolean {
        val band = handOverBand ?: return false
        return !StripDragGeometry.isOutsideBand(band, dnd.toTabs(screen), dnd.horizontal, 0)
    }

    /**
     * Works out the slot under the pointer and applies it to the strip if it changed. A change re-lays the strip out, which can move
     * the placeholder (another row, a pill re-hosted); the pointer is judged again against the new layout until the slot settles
     * (at most [SETTLE_STEPS] times), so one mouse event ends with the placeholder where the pointer is.
     */
    private fun refresh(screen: Point) {
        if (phase != Phase.DRAGGING) return
        repeat(SETTLE_STEPS) { if (!step(screen)) return }
    }

    /** One evaluation; true if it changed the strip. */
    private fun step(screen: Point): Boolean {
        val f = file ?: return false
        val i = info ?: return false
        if (!tabs.isValid) tabs.validate()
        val strip = adapter.stripUnits()
        val cells = planner.cellsOf(strip, f)
        val result = planner.landingFor(strip, cells, f, i.isPinned, slot, screen)
        slot = result.slot
        val before = applied
        if (before?.landing == result) return false
        apply(f, cells, result, groupChanged = before == null || before.landing.groupId != result.groupId)
        return true
    }

    private fun apply(f: VirtualFile, cells: List<StripCell<VirtualFile>>, landing: Landing, groupChanged: Boolean) {
        val flat = dnd.reorder.completeOrder(planner.flatOrder(cells, landing, f, origGroups))
        if (flat != shownFlat) {
            dnd.reorder.reorder(origGroups, flat)
            shownFlat = flat
        }
        // after the groups were re-applied: the preview (pill placement and tint) follows the previewed membership
        adapter.setDragPreview(f, landing.groupId)
        applied = TabDropPlanner.Applied(landing, flat)
        if (groupChanged) {
            if (!tabs.isValid) tabs.validate()
            snapshot() // the picture carries the live tint (DRAG-7)
        }
    }

    // ---- release -------------------------------------------------------------------------------------------------------

    /** Returns true if the release was taken (the caller consumes it). */
    fun mouseReleased(e: MouseEvent): Boolean {
        when (phase) {
            Phase.IDLE -> return false
            Phase.PRESSED -> {
                phase = Phase.IDLE
                return false
            }
            Phase.DRAGGING -> {
                drop(e)
                return true
            }
            Phase.HANDED_OVER -> {
                // a release over the strip always commits in the strip, whatever the last drag event said
                if (isBackOnStrip(Point(RelativePoint(e).screenPoint))) {
                    handBack(e)
                    if (phase == Phase.DRAGGING) drop(e) else finishHandOver(e)
                    return true
                }
                finishHandOver(e)
                return true
            }
            Phase.CANCELLED -> {
                phase = Phase.IDLE
                DragHandOff.resetPlatformHelper(tabs, e)
                return true
            }
        }
    }

    private fun finishHandOver(e: MouseEvent) {
        val h = handOff
        finish()
        try {
            h?.finish(e)
        }
        finally {
            DragHandOff.resetPlatformHelper(tabs, e)
        }
    }

    private fun drop(e: MouseEvent) {
        // judge the release position itself, not only the last drag event
        refresh(Point(RelativePoint(e).screenPoint))
        val f = file
        val a = applied
        val groups = origGroups
        val unchangedOrder = origFlat
        finish()
        DragHandOff.resetPlatformHelper(tabs, e)
        if (f == null) return
        // the strip already shows the result; the preview is removed and the model learns about it
        adapter.clearDragPreview()
        if (a == null) return
        val newGroups = planner.groupsAfterDrop(groups, f, a)
        LOG.info("tab drop: ${f.name} -> ${a.landing.groupId ?: "ungrouped"} at slot ${a.landing.slot}, order changed: ${a.flat != unchangedOrder}")
        if (newGroups == groups && a.flat == unchangedOrder) {
            adapter.setGroups(groups)
            return
        }
        // a group left without a member is removed by the model (MEM-5)
        dnd.model.commit(newGroups)
    }

    // ---- hand-over, cancel, cleanup --------------------------------------------------------------------------------------

    private fun handOver(e: MouseEvent) {
        val i = info ?: return
        val h = DragHandOff(i)
        restoreOriginal()
        dnd.overlay.clear()
        hold.reset()
        handOff = h
        phase = Phase.HANDED_OVER
        LOG.info("tab drag handed over to the platform drag-out: ${file?.name}")
        // dragOutStarted hides the source tab and starts the platform's drag session; the Esc handler stays for dragOutCancelled
        h.start(e)
    }

    /** The pointer is back on the strip: the platform's session is cancelled (the tab is shown again) and the drag goes on here. */
    private fun handBack(e: MouseEvent) {
        val i = info ?: return
        val h = handOff
        handOff = null
        phase = Phase.IDLE
        h?.cancel()
        // dragOutStarted selected another tab; the dragged tab is the one in hand
        if (!i.isHidden) tabs.select(i, false)
        if (!begin()) {
            finish()
            DragHandOff.resetPlatformHelper(tabs, e)
            return
        }
        LOG.info("tab drag handed back from the platform drag-out: ${file?.name}")
        update(e)
    }

    /** Esc (or the tab bar vanishing): back to the order and membership the drag started with. */
    fun cancel() {
        when (phase) {
            Phase.DRAGGING -> {
                restoreOriginal()
                phase = Phase.CANCELLED
                finish(keepPhase = true)
            }
            Phase.HANDED_OVER -> {
                val h = handOff
                phase = Phase.CANCELLED
                finish(keepPhase = true)
                h?.cancel()
            }
            else -> Unit
        }
    }

    private fun restoreOriginal() {
        adapter.clearDragPreview()
        if (shownFlat != origFlat) {
            dnd.reorder.reorder(origGroups, origFlat)
            shownFlat = origFlat
        }
        applied = null
    }

    /** Ends the gesture: ghost, hold timer and Esc handler go away. */
    private fun finish(keepPhase: Boolean = false) {
        hold.reset()
        dnd.overlay.clear()
        removeDispatcher()
        lastPointer = null
        applied = null
        handOff = null
        handOverBand = null
        if (!keepPhase) phase = Phase.IDLE
    }

    private fun removeDispatcher() {
        if (!dispatcherAdded) return
        dispatcherAdded = false
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escape)
    }

    /** A new press arrived while a gesture was still recorded (a lost release): drop all state. */
    private fun abort() {
        if (phase == Phase.DRAGGING) restoreOriginal()
        if (phase == Phase.HANDED_OVER) handOff?.cancel()
        finish()
    }

    fun dispose() {
        hold.reset()
        removeDispatcher()
        phase = Phase.IDLE
    }

    private companion object {
        /** How often one mouse event may re-evaluate the slot after the previous evaluation changed the layout. */
        const val SETTLE_STEPS = 4

        /**
         * How far (unscaled px) beyond the strip's rows, across the strip, the pointer may go before the drag is handed over to the
         * platform's drag-out. The platform's own tolerance: `TabsUtil.UNSCALED_DROP_TOLERANCE` (15), used by
         * `SingleRowLayoutStrategy.adjustDropPoint` and `MultiRowLayout.adjustDropPoint` ("allow the tab to be dragged above or below
         * the tab row without detaching"): up to there the platform still treats the pointer as being on the strip.
         *
         * (The platform's `DragHelper.isDragOut` detaches a tab much earlier, after `ide.tabbedPane.dragOutMultiplier` = 0.1 of the
         * tab's height from the press point; its in-strip phase is then replaced by the drop session, which cannot place a tab next to
         * a pill. That threshold is deliberately not mirrored.)
         */
        const val HAND_OFF_MARGIN_PX = TabsUtil.UNSCALED_DROP_TOLERANCE
    }
}
