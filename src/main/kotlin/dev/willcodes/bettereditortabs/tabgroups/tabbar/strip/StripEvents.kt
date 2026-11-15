package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import java.awt.event.MouseEvent

/**
 * Events from one split's strip to its owner (the `SplitBar`, which forwards them to `TabBarHost`). Internal to `tabbar/`: the
 * strip does not know the host or the split handle. All mouse handling for headers goes through here (see [GroupHeader]).
 */
internal interface StripEvents {
    fun headerClicked(header: GroupHeader, event: MouseEvent) {}
    fun headerRightClicked(header: GroupHeader, event: MouseEvent) {}
    fun headerMiddleClicked(header: GroupHeader, event: MouseEvent) {}

    /** CTX-5: a right click on the free part of the strip (no tab, no pill); [point] is in [component]'s coordinates. */
    fun stripRightClicked(component: javax.swing.JComponent, point: java.awt.Point) {}

    /**
     * The pointer entered ([hovering] true) or left the header (false). A press on the header also ends the hover (false), so a
     * click or a drag never leaves a hover popup behind. INT-5.
     */
    fun headerHoverChanged(header: GroupHeader, hovering: Boolean) {}

    /** A tab that is currently hidden by a collapsed group was selected (navigation, Ctrl+E, ...). COLL-4. */
    fun hiddenTabSelected(groupId: String, file: VirtualFile) {}

    /**
     * IDE-2: the strip re-sorted itself alphabetically; [groups] are the groups last set with their members (and their order) as the
     * strip now shows them. Called after the re-order, at the end of `setGroups` at the latest (its work is done), so the owner may refresh
     * the strip right away; that refresh finds the strip sorted and reports nothing.
     */
    fun stripSorted(groups: List<GroupDecoration>) {}
}
