package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Rectangle

/** Where in an editor split's area the pointer is, as the platform's drop-side test says it ([EditorAreaDrop.sideOf]): the four edges, or the middle. */
enum class EditorSide {
    TOP, LEFT, BOTTOM, RIGHT, CENTER;

    /** The split is made along the other axis: TOP / BOTTOM make a vertical split (a split above or below), LEFT / RIGHT a horizontal one. */
    val splitsVertically: Boolean get() = this == TOP || this == BOTTOM

    /** The new split is the first component of the splitter (above or left of the existing one); the platform's `fileIsSecondaryComponent` is its opposite. */
    val newSplitFirst: Boolean get() = this == TOP || this == LEFT
}

/** What a dragged group becomes on release (INT-6, SPLIT-5), see [GroupDropRules.resolve]. */
enum class GroupLanding {
    /** A tab strip (this split's own, or another split's). */
    STRIP,

    /** The edge of an editor area: a new split with the group in it. */
    SPLIT_EDGE,

    /** The middle of another split's editor area: the group moves to that split. */
    INTO_SPLIT,

    /** Outside every editor area: a detached editor window. */
    NEW_WINDOW,

    /** No target: the group goes back (the middle of its own split, a divider between splits). */
    NONE,
}

/**
 * Pure choice of what a group drag will do on release (INT-6, user request 2026-11-13), unit tested. The platform does the same for
 * a dragged tab: a strip takes it as a tab slot, the edge of an editor area offers a new split (`TabsUtil.getDropSideFor`), the middle
 * adds it to that split, and where no editor accepts it, it makes a window (`DockManagerImpl`: no container under the pointer).
 *
 * Precedence: strip > edge > centre > new window.
 */
object GroupDropRules {
    /**
     * [overStrip]: the pointer is over (or beside) a tab strip. [side]: the drop side of the editor area under the pointer, null if it
     * is over none. [ownSplit]: that area is the dragged group's own split. [overEditorRegion]: the pointer is inside some editor
     * window's split region without being over a split's area (the divider between two splits): not outside the editor, so no window is made.
     */
    fun resolve(overStrip: Boolean, side: EditorSide?, ownSplit: Boolean, overEditorRegion: Boolean): GroupLanding = when {
        overStrip -> GroupLanding.STRIP
        side != null && side != EditorSide.CENTER -> GroupLanding.SPLIT_EDGE
        // the middle of the group's own split: nothing would change (the platform appends the tab to the same split)
        side == EditorSide.CENTER -> if (ownSplit) GroupLanding.NONE else GroupLanding.INTO_SPLIT
        overEditorRegion -> GroupLanding.NONE
        else -> GroupLanding.NEW_WINDOW
    }
}

/** The platform's drop geometry for an editor split, in pure form (`TabsUtil`, `JBTabs.getDropArea`), unit tested. */
object EditorAreaDrop {
    /** Which side of the tab strip holds the tab row. */
    enum class StripSide { TOP, BOTTOM, LEFT, RIGHT, NONE }

    /**
     * The part of a split's [whole] component (tab row and editor) that is its drop area: the component without the tab row
     * (`JBTabs.getDropArea`, [thickness] is the row's height or width). A hidden strip ([StripSide.NONE]) takes nothing off.
     */
    fun dropArea(whole: Rectangle, strip: StripSide, thickness: Int): Rectangle {
        val r = Rectangle(whole)
        val t = thickness.coerceAtLeast(0)
        when (strip) {
            StripSide.TOP -> { r.y += t; r.height -= t }
            StripSide.BOTTOM -> r.height -= t
            StripSide.LEFT -> { r.x += t; r.width -= t }
            StripSide.RIGHT -> r.width -= t
            StripSide.NONE -> {}
        }
        if (r.width < 0) r.width = 0
        if (r.height < 0) r.height = 0
        return r
    }

    /** The highlighted half of [area] for a drop on [side] (`TabsUtil.updateBoundsWithDropSide`): the whole area for the middle. */
    fun highlight(area: Rectangle, side: EditorSide): Rectangle {
        val r = Rectangle(area)
        when (side) {
            EditorSide.TOP -> r.height /= 2
            EditorSide.LEFT -> r.width /= 2
            EditorSide.BOTTOM -> {
                val h = r.height / 2
                r.height -= h
                r.y += h
            }
            EditorSide.RIGHT -> {
                val w = r.width / 2
                r.width -= w
                r.x += w
            }
            EditorSide.CENTER -> {}
        }
        return r
    }

    /** The side from the platform's `TabsUtil` constants (`SwingConstants`: CENTER 0, TOP 1, LEFT 2, BOTTOM 3, RIGHT 4); null for -1 (outside). */
    fun sideOf(swingConstant: Int): EditorSide? = when (swingConstant) {
        0 -> EditorSide.CENTER
        1 -> EditorSide.TOP
        2 -> EditorSide.LEFT
        3 -> EditorSide.BOTTOM
        4 -> EditorSide.RIGHT
        else -> null
    }
}
