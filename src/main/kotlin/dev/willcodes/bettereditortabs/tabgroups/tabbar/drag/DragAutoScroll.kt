package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.tabs.JBTabsPosition
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.model.strip.AutoScroll
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.AWTEvent
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseWheelEvent
import java.util.IdentityHashMap
import javax.swing.Timer

/**
 * Scrolls a strip that is longer than its window while a tab or a group is dragged over it, so the user can reach the tabs that are out
 * of view and drop at the end of the strip (the platform does not scroll during a tab drag). The rules are the pure
 * [AutoScroll]; this class feeds them the live strip and runs a timer.
 *
 * One instance per drag. [update] is called with every pointer move and says which strip the pointer is over (null: none); the timer
 * runs while the pointer is in the edge zone of a strip that overflows and scrolls. Per tick: the layout's scroll offset moves
 * ([TabLayout.scroll] via [PlatformInternals.tabLayout]), the strip is laid out, and [rerun] is called with the last pointer position
 * so the drag works out the slot under the pointer in the scrolled strip, exactly as a mouse move does.
 *
 * Why the strip stayed put in the first attempt (found in the 262 sources, see [PlatformInternals.setMouseInsideTabsArea]): every layout
 * pass of a scrollable strip scrolls back to the selected tab unless the platform believes the mouse is inside the tab area, and during
 * our drag it does not (the press selected the tab, which clears that flag, and the drag events never reach the listener that would set
 * it again). So the flag is set on every strip the drag is on, for the whole drag, and put back when the drag is over ([release]).
 *
 * The mouse wheel works during the drag too ([wheel]): the user can scroll the strip under the pointer by hand as well as by the edge
 * zone. The picture of a drag is shown in a window of its own that follows the pointer ([GhostWindow]), so the operating system delivers
 * a wheel event to that window, which has no scrolling, and nothing in the strip hears it (inside one window AWT would deliver it to the
 * component under the pointer, not to the one the button was pressed on: `Container.LightweightDispatcher.processMouseEvent`, the
 * wheel case retargets to `mouseOver`). A dispatcher of the IDE's event queue ([IdeEventQueue.addDispatcher], public API) sees every
 * wheel event whatever window it was sent to; the one over the strip the drag is on goes to the strip's own wheel handling
 * (`JBTabsImpl`'s wheel listener, `JBTabsImpl.kt:570`: it redirects the event to a hidden scroll pane, whose scroll bar ends in
 * `updateTabsOffsetFromScrollBar` and the layout's `scroll`), exactly as when no drag runs. The strip stays where it is scrolled to for
 * the reason above, and the slot follows the pointer once it has scrolled ([rerun], also during the animation of a smooth wheel
 * scroll: the offset is watched for a moment).
 *
 * What scrolls: the editor tab bar in "one row, scroll tabs panel" mode (`ScrollableSingleRowLayout`, and the unpinned row of
 * `ScrollableMultiRowLayout` with a separate pinned row), for top, bottom, left and right strips alike (left and right scroll vertically).
 * Strips with several rows ("multiple rows", `WrapMultiRowLayout`) and the "squeeze tabs" mode (`CompressibleMultiRowLayout`) have
 * nothing to scroll (`TabLayout.isScrollable` is false), and so does a strip whose tabs all fit (no more button).
 *
 * Wraps `JBTabsImpl.effectiveLayout`, `isMouseInsideTabsArea` (reflection, [PlatformInternals]), `TabLayout.scroll` / `getScrollOffset` /
 * `isScrollable`, `JBTabsImpl.lastLayoutPass` (`moreRect`, `entryPointRect`, `headerRectangle`, `rowCount`, `requiredLength`,
 * `scrollExtent`), `headerFitSize`, `IdeEventQueue.addDispatcher`. EDT only.
 */
internal class DragAutoScroll(private val rerun: (Point) -> Unit) {
    private var strip: TabBarDnd? = null
    private var pointer: Point? = null
    private val timer = Timer(AutoScroll.TICK_MS) { tick() }

    /** The strips whose "mouse inside" flag this drag set, with the value it had before. */
    private val marked = IdentityHashMap<JBEditorTabs, Boolean>()

    /**
     * What the wheel does to a strip ([wheel]): the event, converted to the strip's component, goes to the strip's own wheel handling.
     * A seam for the tests (a headless strip has no wheel listener).
     */
    internal var forward: (TabBarDnd, MouseWheelEvent, Point) -> Unit = ::forwardToStrip

    /**
     * Listen for wheel events of the IDE's event queue from the first pointer move on. Not in a headless environment, where the strip has
     * no wheel handling to forward to either (`JBTabsImpl` installs its wheel listener only when it is not headless); the tests call [wheel].
     */
    internal var listenForWheel = !ApplicationManager.getApplication().isHeadlessEnvironment
    private var wheelListener: Disposable? = null
    private val wheelTimer = Timer(AutoScroll.TICK_MS) { wheelTick() }
    private var wheelOffset = 0
    private var wheelIdleTicks = 0

    /** The timer is running (the pointer is in the edge zone of a scrollable strip). */
    val isScrolling: Boolean get() = timer.isRunning

    /** The strips that have the "mouse inside" flag set by this drag (tests). */
    internal val heldStrips: Set<JBEditorTabs> get() = marked.keys

    /** The pointer is at [screen] over [over] (null: over no strip). Starts or stops the timer. */
    fun update(over: TabBarDnd?, screen: Point) {
        pointer = Point(screen)
        strip = over
        if (over != null) {
            // the strip the drag is on is "mouse inside" for the whole drag: no layout pass scrolls it back to the dragged tab
            hold(over.tabs)
            if (listenForWheel && wheelListener == null) startListeningForWheel()
        }
        if (over != null && stepFor(over, screen) != 0) {
            if (!timer.isRunning) timer.start()
        }
        else timer.stop()
    }

    /** The timers stop; the strips keep what they show. */
    fun stop() {
        timer.stop()
        wheelTimer.stop()
        strip = null
        pointer = null
    }

    /** The drag is over: the timers stop and every strip gets back the platform's own "mouse inside" flag. */
    fun release() {
        stop()
        wheelListener?.let { Disposer.dispose(it) }
        wheelListener = null
        for ((tabs, before) in marked) PlatformInternals.setMouseInsideTabsArea(tabs, before)
        marked.clear()
    }

    /** The signed scroll step for a pointer at [screen] over [dnd]'s strip: 0 outside the edge zones or if there is nothing to scroll. */
    fun stepFor(dnd: TabBarDnd, screen: Point): Int {
        if (!dnd.isShowing) return 0
        val tabs = dnd.tabs
        val layout = PlatformInternals.tabLayout(tabs) ?: return 0
        if (!layout.isScrollable) return 0
        val pass = tabs.lastLayoutPass ?: return 0
        if (pass.moreRect.isEmpty) return 0 // everything fits: no more button
        val horizontal = dnd.horizontal
        val rowHeight = tabs.headerFitSize?.height ?: 0
        val band = AutoScroll.scrollingBand(pass.headerRectangle, pass.rowCount, rowHeight, horizontal, tabs.tabsPosition == JBTabsPosition.top)
                   ?: return 0
        val p = dnd.toTabs(screen)
        if (!AutoScroll.inBand(if (horizontal) p.y else p.x, band, JBUI.scale(TOLERANCE_PX))) return 0
        val end = AutoScroll.spanEnd(
            if (horizontal) tabs.width else tabs.height, listOf(pass.moreRect, pass.entryPointRect), band.first, band.last + 1, horizontal,
        )
        return AutoScroll.step(
            if (horizontal) p.x else p.y, 0, end,
            JBUI.scale(AutoScroll.ZONE_PX), JBUI.scale(AutoScroll.MIN_STEP_PX), JBUI.scale(AutoScroll.MAX_STEP_PX),
        )
    }

    /** One timer tick (also what the tests call). */
    fun tick() {
        val dnd = strip ?: return stopTimer()
        val p = pointer ?: return stopTimer()
        val step = stepFor(dnd, p)
        if (step == 0) return stopTimer()
        val tabs = dnd.tabs
        val layout = PlatformInternals.tabLayout(tabs) ?: return stopTimer()
        val before = layout.scrollOffset
        var move = step
        if (!PlatformInternals.layoutClampsOffset(layout)) {
            val pass = tabs.lastLayoutPass ?: return
            move = AutoScroll.clampedStep(before, step, pass.requiredLength, pass.scrollExtent)
        }
        if (move == 0) return // at the end of the strip; the pointer may still come back
        hold(tabs)
        layout.scroll(move)
        if (layout.scrollOffset == before) return
        followScroll(dnd)
    }

    private fun stopTimer() {
        timer.stop()
    }

    // ---- the mouse wheel ---------------------------------------------------------------------------------------------------

    private fun startListeningForWheel() {
        val disposable = Disposer.newDisposable("DragAutoScroll.wheel")
        // A dispatcher that needs no lock only decides (geometry); the scrolling itself runs right after, as an ordinary EDT task.
        IdeEventQueue.getInstance().addDispatcher(object : IdeEventQueue.NonLockedEventDispatcher {
            override fun dispatch(e: AWTEvent): Boolean {
                if (e !is MouseWheelEvent || strip == null) return false
                val screen = try {
                    Point(RelativePoint(e).screenPoint)
                }
                catch (ex: Exception) {
                    return false // the component the event was sent to is not showing any more
                }
                if (!claims(e, screen)) return false
                ApplicationManager.getApplication().invokeLater({ if (strip != null) scrollWith(e, screen) }, ModalityState.any())
                return true
            }
        }, disposable)
        wheelListener = disposable
    }

    /** Is the wheel event at [screen] over the strip the drag is on (and not used by anyone else)? */
    private fun claims(e: MouseWheelEvent, screen: Point): Boolean {
        val dnd = strip ?: return false
        return !e.isConsumed && dnd.isShowing && dnd.stripScreenRect()?.contains(screen) == true
    }

    /**
     * A wheel event at [screen] during the drag. If the pointer is over the strip the drag is on, the strip scrolls as it does for any
     * wheel event ([forward]) and the slot follows; returns true then (the event is used up, so the window that got it does not see it).
     * Over anything else it is left alone.
     */
    fun wheel(e: MouseWheelEvent, screen: Point): Boolean {
        if (!claims(e, screen)) return false
        scrollWith(e, screen)
        return true
    }

    private fun scrollWith(e: MouseWheelEvent, screen: Point) {
        val dnd = strip ?: return
        hold(dnd.tabs)
        wheelOffset = PlatformInternals.tabLayout(dnd.tabs)?.scrollOffset ?: 0
        forward(dnd, e, screen)
        followScroll(dnd)
        wheelIdleTicks = 0
        if (!wheelTimer.isRunning) wheelTimer.start()
    }

    /** The wheel scrolls smoothly (an animation of several frames, each moving the layout's offset): the slot follows every step. */
    internal fun wheelTick() {
        val dnd = strip ?: return wheelTimer.stop()
        val offset = PlatformInternals.tabLayout(dnd.tabs)?.scrollOffset ?: return wheelTimer.stop()
        if (offset != wheelOffset) {
            wheelOffset = offset
            wheelIdleTicks = 0
            followScroll(dnd)
        }
        else if (++wheelIdleTicks > WHEEL_IDLE_TICKS) wheelTimer.stop()
    }

    /**
     * The strip has scrolled: it is laid out now (not at the next paint) and the slot is worked out against it, as for a mouse move.
     * `invalidate` first: `revalidate` does nothing for a component that has no parent yet, and the strip must not look valid to `validate`.
     */
    private fun followScroll(dnd: TabBarDnd) {
        val tabs = dnd.tabs
        tabs.invalidate()
        tabs.revalidate()
        dnd.validateNow()
        pointer?.let { rerun(it) }
        hold(tabs)
    }

    private fun forwardToStrip(dnd: TabBarDnd, e: MouseWheelEvent, screen: Point) {
        val component = dnd.tabs.component
        val p = dnd.toTabs(screen)
        val buttons = InputEvent.BUTTON1_DOWN_MASK or InputEvent.BUTTON2_DOWN_MASK or InputEvent.BUTTON3_DOWN_MASK
        // the button that is held for the drag is not part of the wheel gesture
        component.dispatchEvent(
            MouseWheelEvent(
                component, e.id, e.`when`, e.modifiersEx and buttons.inv(), p.x, p.y, screen.x, screen.y, 0, false,
                e.scrollType, e.scrollAmount, e.wheelRotation, e.preciseWheelRotation,
            ),
        )
    }

    /** The platform must not scroll the strip back to the selected tab on the next layout pass. */
    private fun hold(tabs: JBEditorTabs) {
        if (tabs !in marked) marked[tabs] = PlatformInternals.mouseInsideTabsArea(tabs) ?: false
        if (PlatformInternals.mouseInsideTabsArea(tabs) != true) PlatformInternals.setMouseInsideTabsArea(tabs, true)
    }

    private companion object {
        /** Ticks (about 16 ms each) without a change of the scroll offset after which a wheel scroll counts as over. */
        const val WHEEL_IDLE_TICKS = 30

        /** How far beside its rows the pointer still counts as on the strip (the platform's drop tolerance, unscaled). */
        const val TOLERANCE_PX = 15
    }
}
