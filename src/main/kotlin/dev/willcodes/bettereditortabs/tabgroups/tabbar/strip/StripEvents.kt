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

    /** A tab that is currently hidden by a collapsed group was selected (navigation, Ctrl+E, ...). COLL-4. */
    fun hiddenTabSelected(groupId: String, file: VirtualFile) {}
}
