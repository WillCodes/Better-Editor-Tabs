package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color

/**
 * What the tab bar of one split must show for one group instance. Plain data: the adapter owns all platform state.
 *
 * @param members the group's files in tab order. Files that are not open in the split are ignored.
 * @param name empty means "unnamed": the header is drawn as a small dot (HDR-4).
 */
data class GroupDecoration(
    val id: String,
    val name: String,
    val color: Color,
    val members: List<VirtualFile>,
    val collapsed: Boolean,
)
