package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.ScreenUtil
import com.intellij.ui.awt.RelativePoint
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EditorAreaDrop.StripSide
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PopupPlacementRules
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

/**
 * Where the group popup (§6) and the hover list (INT-5) open relative to their header: directly below it, aligned to the pill's
 * leading edge, for a strip on top (Chrome style), and on the side facing the editor for the other tab placements (above for Bottom,
 * right for Left, left for Right), flipped or shifted to stay on screen ([PopupPlacementRules]). The returned point is the popup's
 * top-left corner.
 *
 * The popup's size is its content's preferred size, which is what `AbstractPopup.showImpl` sizes the window to (plus the border's
 * insets, a few pixels). Note for Stage 11: a popup opened *above* the header (Bottom placement) grows downwards when it is re-packed
 * (the Advanced section), over the header; it must be placed again after `pack`.
 */
internal object PopupPlacement {
    /** The point to show [popup] at for [header]: see the class comment. */
    fun beside(header: HeaderRef, popup: JBPopup): RelativePoint {
        val component = header.component
        val visible = component.visibleRect
        val horizontal = header.stripSide != StripSide.LEFT && header.stripSide != StripSide.RIGHT
        val pillLeading = if (horizontal) maxOf(header.pillBounds.x, visible.x) else maxOf(header.pillBounds.y, visible.y)
        if (!component.isShowing) return RelativePoint(component, Point(if (horizontal) pillLeading else 0, component.height))

        val origin = component.locationOnScreen
        // Along the strip only the visible part counts (a scrolled header); across it the whole component.
        val headerRect = if (horizontal) Rectangle(origin.x + visible.x, origin.y, visible.width, component.height)
        else Rectangle(origin.x, origin.y + visible.y, component.width, visible.height)
        var screen = ScreenUtil.getScreenRectangle(component)
        if (screen.isEmpty) screen = Rectangle(Int.MIN_VALUE / 2, Int.MIN_VALUE / 2, Int.MAX_VALUE, Int.MAX_VALUE)
        val size: Dimension = popup.content.preferredSize
        val leading = (if (horizontal) origin.x else origin.y) + pillLeading
        val at = PopupPlacementRules.place(headerRect, leading, size, screen, header.stripSide)
        return RelativePoint(component, Point(at.x - origin.x, at.y - origin.y))
    }
}
