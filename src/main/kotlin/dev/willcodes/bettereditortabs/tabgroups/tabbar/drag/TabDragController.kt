package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.tabs.JBTabsEx
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.InplaceButton
import com.intellij.ui.MouseDragHelper
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.tabs.TabInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripDragGeometry
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

private val LOG = logger<TabDragController>()

/**
 * The gesture of a tab drag, instead of letting the platform's `DragHelper` swap labels: press, drag threshold, release, Esc, and the
 * hand-over to and back from the platform's drag-out. What happens on the strips during the drag is [CrossSplitTabDrag] (which strip
 * the pointer is over, [TabDragTargets]; the live work on that strip, [TabDragSession]); where the tab lands is [TabDropPlanner].
 *
 * Why: `DragHelper` only swaps the dragged label with other *labels*. A group pill is not a label (its space is borrowed inside a
 * neighbouring label), so the platform could never drop a tab between a pill and its neighbours, and its drop on another split's
 * strip (`JBTabsImpl.processDropOver` -> `SingleRowLayout.getDropIndexFor`: `getComponentAt(point)` finds our `GroupHeader`, not a
 * label, so the index is -1 and the platform makes a new split) never worked in front of a pill. Here the strip is a sequence of
 * cells (tabs and pills, `TabBarAdapter.stripUnits`) and the pointer picks a slot between two cells ([DropSlots.select]); the pill
 * is a cell of its own with its own hit area, in this split's strip and in any other split's.
 *
 * What the user sees is what the platform shows for a tab drag in the New UI (the drag-out phase of `DragHelper`, i.e. the drop
 * session of `DockManagerImpl`): the dragged tab's place is an empty slot (`StripDragSurface.setDragGap`, the look of
 * `TabLabel.paint` for a drop target, `TabLabel.kt:321-339`) and one picture of the tab floats centred on the pointer
 * (`DockManagerImpl.kt:236-243`, 90 % opaque, [DragOverlay]); there is no second copy of the tab in the strip. The picture is
 * the platform's own tab picture, made again whenever the previewed membership changes, so it carries the live tint (DRAG-7); the slot
 * stays neutral like the platform's.
 *
 * Gesture: a left press on a tab label (not on a pill, not on a close button) is only remembered; nothing is consumed, the tab is
 * selected as usual and clicks, middle-click and the context menu work. Once the pointer moved past the platform's drag threshold
 * the drag is ours: [DragObserver] consumes the drag events so `DragHelper` never starts.
 * - Over a tab strip (this split's or another split's, see [TabDragTargets]): a [TabDragSession] re-orders the strip live (or opens a
 *   gap in another split's strip) whenever the slot (or the group the tab would join) changes and shows the previewed pill and tint
 *   (DRAG-7); membership is only committed to the model on release ([SplitGroups.commit]); a release over another split's strip moves
 *   the file there ([moveTabToSplit], DRAG-5). Esc restores order and membership everywhere.
 * - Pointer off every strip (more than the platform's drop tolerance beyond a strip's rows, or past its ends): the strips are
 *   restored and the gesture is handed over to the platform's drag-out ([DragHandOff]); from then on it is exactly the platform drag
 *   (split edges, a new window, tool windows). If the pointer comes back onto any strip the platform's session is cancelled and the
 *   drag continues here (a new drag), so a release over a strip always commits there. The same for the editor area of a split that shows
 *   no tab but the dragged one ([CrossSplitTabDrag], [dev.willcodes.bettereditortabs.tabgroups.model.strip.TabEditorDrop]): the
 *   platform's session offers no split edge there, so the drag stays ours and the edge makes the new split.
 * - With the platform's alphabetical sorting on and no group in the strip, the press is ignored and the platform's own drag runs (it
 *   re-sorts the strip itself and turns sorting off after a manual drag); so does a drag the platform starts itself (a file from the
 *   Project view). A strip with groups is sorted by the plugin ([dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripSort],
 *   IDE-2): the drag runs as usual with join / leave of groups, and after the drop the sort puts the tab where it belongs.
 *
 * Internal APIs wrapped: `JBTabsImpl` (tab labels, `TabInfo.dragOutDelegate`, `DragHelper` press state). EDT only.
 */
internal class TabDragController(private val dnd: TabBarDnd) {
    private enum class Phase { IDLE, PRESSED, DRAGGING, HANDED_OVER, CANCELLED }

    private var phase = Phase.IDLE
    private val tabs get() = dnd.tabs

    private var file: VirtualFile? = null
    private var info: TabInfo? = null
    private var pressScreen = Point()

    private var drag: CrossSplitTabDrag? = null
    private var handOff: DragHandOff? = null
    private var dispatcherAdded = false

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
        // Sorted alphabetically with no groups here, the platform re-sorts the strip itself and turns sorting off after a manual drag:
        // leave it alone. A strip with groups is sorted by the plugin (IDE-2, StripSort): the drag runs, and its drop (a new membership)
        // is followed by the sort, which decides the position.
        if (UISettings.getInstance().sortTabsAlphabetically && !dnd.adapter.ownsSort) return
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
                if (!begin(screen)) {
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
                if (takesBack(Point(RelativePoint(e).screenPoint))) handBack(e) else handOff?.process(e)
                return true
            }
            Phase.CANCELLED -> return true
        }
    }

    /** Starts (or, after a hand-back, restarts) the drag on the strips with the pointer at [screen]; adds the Esc handler. */
    private fun begin(screen: Point): Boolean {
        val f = file ?: return false
        val i = info ?: return false
        val d = CrossSplitTabDrag(dnd, DraggedTab(f, i, dnd))
        if (!d.begin(screen)) return false
        drag = d
        if (!dispatcherAdded) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escape)
            dispatcherAdded = true
        }
        phase = Phase.DRAGGING
        return true
    }

    private fun update(e: MouseEvent) {
        val d = drag ?: return
        val screen = Point(RelativePoint(e).screenPoint)
        if (!tabs.isShowing) {
            cancel()
            return
        }
        if (!d.move(screen) && DragHandOff(info ?: return).available) handOver(e)
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
                if (takesBack(Point(RelativePoint(e).screenPoint))) {
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

    /**
     * The pointer at [screen] is back on a strip, or over the editor area of a split whose strip shows no tab but the dragged one (a "bare"
     * split: the platform's drop session would see no visible tab there and offer no split edge, Stage 11.5 fix round 5): the drag is ours.
     */
    private fun takesBack(screen: Point): Boolean {
        val d = drag ?: return false
        return d.targets.stripAt(screen) != null || d.overBareEditor(screen)
    }

    private fun finishHandOver(e: MouseEvent) {
        val h = handOff
        finish()
        try {
            if (dropsOnItsOwnEmptySplit()) {
                // the split's only tab released on its own editor: the platform would close the tab first, which closes the split, and
                // then open the file in the split that is current by then, another one (user report, Stage 11.5 fix round 4); the tab
                // stays where it is instead, as if it had not moved
                LOG.info("tab drag: the only tab of its split dropped on its own editor: nothing moves")
                h?.cancel()
            }
            else h?.finish(e)
        }
        finally {
            DragHandOff.resetPlatformHelper(tabs, e)
        }
    }

    /**
     * The release is over this strip's own editor area (the platform's drop target is this tab bar: `JBTabsEx.dropSide` is set while
     * `DockableEditorTabbedContainer` is over it, -1 otherwise, `JBTabsImpl.resetDropOver`) and the split has no tab but the dragged one
     * (`EditorWindow.tabCount` counts hidden tabs: a collapsed group's or a temporary split's placeholder keep the split alive, and then the
     * platform's own drop is fine).
     */
    private fun dropsOnItsOwnEmptySplit(): Boolean {
        val side = (tabs as? JBTabsEx)?.dropSide ?: return false
        return side != -1 && dnd.adapter.window.tabCount <= 1
    }

    private fun drop(e: MouseEvent) {
        val d = drag
        val screen = Point(RelativePoint(e).screenPoint)
        phase = Phase.IDLE
        DragHandOff.resetPlatformHelper(tabs, e)
        // the strips show the result already; the preview is removed and the model learns about it (Ctrl: the platform's copy modifier)
        d?.drop(screen, UIUtil.isControlKeyDown(e))
        finish()
    }

    // ---- hand-over, cancel, cleanup --------------------------------------------------------------------------------------

    private fun handOver(e: MouseEvent) {
        val i = info ?: return
        val h = DragHandOff(i)
        // the drag keeps the extents of the strips: they decide when the pointer is back on one
        drag?.handOver()
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
        if (!begin(Point(RelativePoint(e).screenPoint))) {
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
                drag?.cancel()
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

    /** Ends the gesture: the drag (picture, sessions) and the Esc handler go away. */
    private fun finish(keepPhase: Boolean = false) {
        drag?.finish()
        drag = null
        removeDispatcher()
        handOff = null
        if (!keepPhase) phase = Phase.IDLE
        dnd.adapter.recheckSort() // IDE-2: a cancelled or handed-over drag ends sorted too
    }

    private fun removeDispatcher() {
        if (!dispatcherAdded) return
        dispatcherAdded = false
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escape)
    }

    /** A new press arrived while a gesture was still recorded (a lost release): drop all state. */
    private fun abort() {
        if (phase == Phase.DRAGGING) drag?.cancel()
        if (phase == Phase.HANDED_OVER) handOff?.cancel()
        finish()
    }

    fun dispose() {
        drag?.dispose()
        drag = null
        removeDispatcher()
        phase = Phase.IDLE
    }
}
