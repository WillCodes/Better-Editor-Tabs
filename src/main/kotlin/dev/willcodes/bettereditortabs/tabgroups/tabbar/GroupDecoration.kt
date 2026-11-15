package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.vfs.VirtualFile
import java.awt.Color

/**
 * What the tab bar of one split must show for one group instance. Plain data: the adapter owns all platform state.
 *
 * @param members the group's files in tab order. Files that are not open in the split are ignored.
 * @param name empty means "unnamed": the header is drawn as a small dot.
 * @param creationIndex the group's place in the project's creation order (0 = oldest). With alphabetical sorting, unnamed
 *   groups follow the named ones in this order. Linked instances of one group carry the same value.
 * @param peek partial expand: while [collapsed], the one member shown beside the header (the others stay hidden and a "+N"
 *   count follows it); null = fully collapsed. Ignored when the group is expanded or the file is not a member open in the split.
 */
data class GroupDecoration(
    val id: String,
    val name: String,
    val color: Color,
    val members: List<VirtualFile>,
    val collapsed: Boolean,
    val creationIndex: Int = 0,
    val peek: VirtualFile? = null,
)
