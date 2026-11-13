package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.ui.tabs.TabsUtil
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripTargetRules
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripView
import java.awt.Point
import java.awt.Rectangle
import java.util.IdentityHashMap

/**
 * Which strip a tab drag is over (DRAG-0, DRAG-5): the [home] strip it was picked up from, another split's strip, or none (then the
 * drag belongs to the platform's drag-out, [DragHandOff]). The decision is the pure [StripTargetRules.choose]; this class feeds it
 * the live strips (`TabBarDnd.stripScreenRect` of every split of the project, [TabBarDnd.peers]) and remembers the widest each strip
 * has been, which is what makes the choice stable while the drag itself changes the strips.
 *
 * "Beside the rows" is [STICKY_BESIDE_PX]: with the 8 px that `stripScreenRect` already adds, the pointer may go 15 px
 * (`TabsUtil.UNSCALED_DROP_TOLERANCE`, the platform's own tolerance for a drop on a strip) beyond a strip's rows before the drag leaves
 * it, which is where the drag was handed over before it could continue in other splits.
 *
 * [eligible] says which strips may take the tab at all (a strip that is not showing, or in which the file is open but hidden, may not).
 * EDT only.
 */
internal class TabDragTargets(private val home: TabBarDnd, private val eligible: (TabBarDnd) -> Boolean) {
    private val reach = IdentityHashMap<TabBarDnd, Rectangle>()

    /** The strip the drag is on now: its sticky margin counts while it is not the home strip. */
    var current: TabBarDnd? = null
        private set

    /** A new drag, or the drag comes back from the platform: forget what the strips looked like. */
    fun restart() {
        reach.clear()
        current = null
    }

    /** The drag has left [dnd] (its gap is closed): its widest extent is forgotten, so it does not claim the pointer any more. */
    fun forget(dnd: TabBarDnd) {
        if (dnd !== home) reach.remove(dnd)
        if (current === dnd) current = null
    }

    /** The strip [screen] is over, per [StripTargetRules.choose]; the strips' extents are updated first. Null: over none. */
    fun choose(screen: Point): TabBarDnd? {
        val views = views(updateReach = true)
        val margin = JBUI.scale(STICKY_BESIDE_PX)
        val found = StripTargetRules.choose(screen, views, home, current, margin)
        current = found
        return found
    }

    /**
     * After the hand-over to the platform: the strip [screen] is on, if any, judged without any margin and against what each strip
     * was (the home strip's tab is hidden by the platform then, a strip of one tab would have no rows left). Null: still off the strips.
     */
    fun stripAt(screen: Point): TabBarDnd? = StripTargetRules.stripAt(screen, views(updateReach = false), home)

    /** The strips of every split that can take the tab, with the extent they have now and the widest they have been. */
    private fun views(updateReach: Boolean): List<StripView<TabBarDnd>> = home.peers().filter { it === home || eligible(it) }.map { dnd ->
        val now = dnd.stripScreenRect()
        if (updateReach && now != null) reach[dnd] = reach[dnd]?.union(now) ?: Rectangle(now)
        StripView(dnd, now, reach[dnd], dnd.horizontal)
    }

    companion object {
        /** The platform's drop tolerance (15 px) less the tolerance `stripScreenRect` has already added (8 px), unscaled. */
        const val STICKY_BESIDE_PX = TabsUtil.UNSCALED_DROP_TOLERANCE - 8
    }
}
