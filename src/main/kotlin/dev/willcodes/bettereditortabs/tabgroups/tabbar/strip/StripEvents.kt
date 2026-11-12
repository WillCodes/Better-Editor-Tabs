package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import java.awt.event.MouseEvent

/**
 * Events from one split's strip to its owner (the `SplitBar`, which forwards them to `TabBarHost`). Internal to `tabbar/`: the
 * strip does not know the host or the split handle. All mouse handling for headers goes through here (see [GroupHeader]).
 */
internal interface StripEvents {
    fun headerClicked(header: GroupHeader, event: MouseEvent) {}
    fun headerRightClicked(header: GroupHeader, event: MouseEvent) {}
    fun headerMiddleClicked(header: GroupHeader, event: MouseEvent) {}

    /**
     * The pointer entered ([hovering] true) or left the header (false). A press on the header also ends the hover (false), so a
     * click or a drag never leaves a hover popup behind. INT-5.
     */
    fun headerHoverChanged(header: GroupHeader, hovering: Boolean) {}

    /** A tab that is currently hidden by a collapsed group was selected (navigation, Ctrl+E, ...). COLL-4. */
    fun hiddenTabSelected(groupId: String, file: VirtualFile) {}
}
