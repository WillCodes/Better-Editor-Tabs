package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import java.awt.Rectangle

/**
 * One visible element of the tab strip, in strip order (what the user sees: left to right, or top to bottom in the side placements;
 * in a multi-row strip row by row). See [TabBarAdapter.stripUnits].
 *
 * A pill is a unit of its own, even though its layout space is borrowed from a tab label (see [TabLabelSpace]): its [rect] is the
 * pill's own slot, and the [Tab.rect] of the host tab does not include it.
 */
internal sealed class StripUnit {
    /** The element's own area in tab-component coordinates, cut to the visible part of the strip. Empty when scrolled out of view. */
    abstract val rect: Rectangle

    /** [groupId] is the group the tab belongs to for pill placement and tint, i.e. with a drag preview applied; null = no group. */
    data class Tab(val file: VirtualFile, override val rect: Rectangle, val groupId: String?, val pinned: Boolean) : StripUnit()

    /** The pill of a group; a collapsed group is exactly one pill ([collapsed] = true) at the place of the group. */
    data class Pill(val groupId: String, override val rect: Rectangle, val collapsed: Boolean, val header: GroupHeader) : StripUnit()
}
