package dev.willcodes.bettereditortabs.tabgroups.model.strip

import dev.willcodes.bettereditortabs.tabgroups.model.strip.EditorAreaDrop.StripSide
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

/**
 * Where the group popup and the hover list open, in screen coordinates. They open directly below the header of a strip on top
 * (Chrome style), and on the side facing the editor for the other placements: above the header for Bottom, right of it for Left,
 * left of it for Right. If the popup does not fit there it flips to the opposite side when that has more room, and it is shifted
 * along the strip to stay on screen.
 */
object PopupPlacementRules {
    /**
     * The popup's top-left corner. [header] is the visible part of the header component on screen; [pillLeading] the pill's leading edge
     * on screen along the strip (x for [StripSide.TOP] / [StripSide.BOTTOM], y for [StripSide.LEFT] / [StripSide.RIGHT]), which the popup
     * is aligned to when it fits; [popup] its size; [screen] the usable screen area. [StripSide.NONE] places like [StripSide.TOP].
     */
    fun place(header: Rectangle, pillLeading: Int, popup: Dimension, screen: Rectangle, side: StripSide): Point =
        when (side) {
            StripSide.LEFT, StripSide.RIGHT -> {
                val x = across(header.x, header.x + header.width, popup.width, screen.x, screen.x + screen.width, after = side == StripSide.LEFT)
                Point(x, along(pillLeading, popup.height, screen.y, screen.y + screen.height))
            }
            else -> {
                val y = across(header.y, header.y + header.height, popup.height, screen.y, screen.y + screen.height, after = side != StripSide.BOTTOM)
                Point(along(pillLeading, popup.width, screen.x, screen.x + screen.width), y)
            }
        }

    /** The popup's start on the axis across the strip: after the header ([after]) or before it, flipped when it does not fit and the other side has more room. */
    private fun across(headerStart: Int, headerEnd: Int, size: Int, screenStart: Int, screenEnd: Int, after: Boolean): Int {
        val roomAfter = screenEnd - headerEnd
        val roomBefore = headerStart - screenStart
        val preferredRoom = if (after) roomAfter else roomBefore
        val otherRoom = if (after) roomBefore else roomAfter
        val useAfter = if (size > preferredRoom && otherRoom > preferredRoom) !after else after
        val start = if (useAfter) headerEnd else headerStart - size
        return start.coerceAtMost(screenEnd - size).coerceAtLeast(screenStart)
    }

    /** The popup's start along the strip: [leading] when it fits, else shifted back onto the screen; the screen's start if it is larger than the screen. */
    private fun along(leading: Int, size: Int, screenStart: Int, screenEnd: Int): Int =
        leading.coerceAtMost(screenEnd - size).coerceAtLeast(screenStart)
}
