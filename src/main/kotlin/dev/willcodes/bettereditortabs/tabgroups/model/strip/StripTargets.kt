package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Point
import java.awt.Rectangle

/**
 * One tab strip as the choice of a drag target sees it (screen coordinates): [rect] is where the strip is now (null: not showing),
 * [reach] the widest it has been during this drag (null: not known yet), [horizontal] its orientation.
 */
class StripView<K>(val key: K, val rect: Rectangle?, val reach: Rectangle?, val horizontal: Boolean)

/**
 * Pure choice of the strip a tab drag is over, unit tested. The drag starts in the [home] strip and may continue in
 * any other split's strip; the pointer is over none of them while it is on an editor, a tool window, a split edge or outside the window.
 *
 * Precedence, the same as for a group drag (`HeaderDragHelper.updateTarget`):
 * 1. the home strip itself;
 * 2. another strip itself;
 * 3. the strip the drag is on now ([current], if it is not the home strip) while the pointer is just beside its rows;
 * 4. the home strip while the pointer is just beside its rows.
 * "Just beside" is [margin] across the strip from the widest it has been ([StripDragGeometry.beside]): opening a gap or re-ordering
 * live can change the number of rows of a wrapped strip, so its rectangle shrinks and grows because of the drag itself, and judging
 * against the live rectangle alone would flip the drag between the strips and the platform's drag-out over and over. Along the strip
 * there is no such margin: moving past its ends leaves it.
 */
object StripTargetRules {
    fun <K> choose(pointer: Point, strips: List<StripView<K>>, home: K, current: K?, margin: Int): K? {
        val homeView = strips.firstOrNull { it.key == home }
        if (homeView?.rect?.contains(pointer) == true) return home
        strips.firstOrNull { it.key != home && it.rect?.contains(pointer) == true }?.let { return it.key }
        fun sticky(v: StripView<K>?): Boolean = v?.reach?.let { StripDragGeometry.beside(it, v.horizontal, margin).contains(pointer) } == true
        if (current != null && current != home && sticky(strips.firstOrNull { it.key == current })) return current
        return if (sticky(homeView)) home else null
    }

    /**
     * The strip containing [pointer] itself (no margins), the home strip first, judged against the widest it has been (the platform
     * hides the dragged tab of the home strip once the drag is handed over, which can leave it no rows); used to take a drag back from the platform.
     */
    fun <K> stripAt(pointer: Point, strips: List<StripView<K>>, home: K): K? {
        val homeView = strips.firstOrNull { it.key == home }
        if (homeView != null && (homeView.reach ?: homeView.rect)?.contains(pointer) == true) return home
        return strips.firstOrNull { it.key != home && it.rect?.contains(pointer) == true }?.key
    }
}
