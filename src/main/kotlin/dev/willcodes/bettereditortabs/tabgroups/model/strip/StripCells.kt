package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Rectangle

/**
 * The strip as the drop rules see it (DRAG-1..DRAG-7, INT-6): a sequence of cells, ungrouped tabs, group member tabs and group pills.
 * A collapsed group is a single pill cell (its hidden members are not cells). The dragged tab is not part of the sequence; the
 * question is which of the gaps ("slots", 0..cells.size) it is dropped into and which group it belongs to there.
 */
/** One element of the strip as the drop rules see it. [rect] is in tab-bar coordinates and excludes space reserved for a pill. */
sealed class StripCell<T> {
    abstract val rect: Rectangle
}

data class TabCell<T>(val key: T, val groupId: String?, val pinned: Boolean, override val rect: Rectangle) : StripCell<T>()

data class PillCell<T>(val groupId: String, val collapsed: Boolean, override val rect: Rectangle) : StripCell<T>()

/**
 * The outcome of dropping a tab into a slot.
 *
 * @param slot where the tab goes in the cell sequence (the requested slot, moved out of a group for a pinned tab).
 * @param groupId the group the tab belongs to there, null if none.
 * @param trailingGroup the group whose trailing slot this is (right behind its last tab): the tab stays outside until the pointer
 *   has been held there ([DragHoldState]), then joins. Null for any other slot, for pinned tabs and for collapsed groups.
 */
data class Landing(val slot: Int, val groupId: String?, val trailingGroup: String?)

/** A unit that a group drag may be dropped between: one ungrouped tab, or one whole group instance. */
data class MoveUnit<T>(
    val groupId: String?,
    val keys: List<T>,
    val bounds: Rectangle,
    val pinned: Boolean = false,
    /** The part of [bounds] in the unit's first row (a group that wraps); equals [bounds] otherwise. */
    val lead: Rectangle = bounds,
    /** The pill of a group unit, null for a tab. */
    val pill: Rectangle? = null,
    /** A collapsed group (only its pill is on screen). */
    val collapsed: Boolean = false,
    /**
     * What the unit covers on screen, one rectangle per row it is on (a group that wraps over rows in multi-row mode); [bounds] is
     * their union, which also covers other tabs of those rows. An outline of the unit draws these.
     */
    val runs: List<Rectangle> = listOf(bounds),
)
