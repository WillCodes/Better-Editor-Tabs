package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.ThreadingAssertions
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EditorSide
import dev.willcodes.bettereditortabs.tabgroups.model.strip.MoveUnit
import dev.willcodes.bettereditortabs.tabgroups.tabbar.drag.freezeOwn
import dev.willcodes.bettereditortabs.tabgroups.tabbar.drag.moveGroupToNewSplit
import dev.willcodes.bettereditortabs.tabgroups.tabbar.drag.moveGroupToSplit
import dev.willcodes.bettereditortabs.tabgroups.tabbar.ide.GroupReopen
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.Rectangle

/**
 * The operations of [TabBar] that move a group between splits or open its files, without a drag by the user: Split Right / Down,
 * Move to another split, opening files in a given order and placing an empty
 * instance's pill. They build [MoveUnit]s and run the drag code's group moves, or [GroupReopen]. EDT only.
 */
internal class GroupTransfers(
    private val project: Project,
    private val tabBar: TabBar,
    private val registry: SplitRegistry,
) {
    /** See [TabBar.splitGroup]. */
    fun splitGroup(split: SplitHandle, groupId: String, down: Boolean): Boolean {
        ThreadingAssertions.assertEventDispatchThread()
        if (tabBar.host == null || split.window.isDisposed) return false
        val bar = registry.barFor(split) ?: return false
        val group = bar.groups().firstOrNull { it.id == groupId } ?: return false
        // the group's open tabs in the order the strip shows them (a collapsed group's hidden tabs included): what a drop of the header carries
        val members = group.members.toHashSet()
        val keys = tabBar.logicalOrder(split).filter { it in members }
        if (keys.isEmpty()) return false
        val unit = MoveUnit(groupId, keys, Rectangle(), false, Rectangle(), null, group.collapsed)
        // the Ctrl-drop of the header on the editor's right / bottom edge, without a drag: a linked copy in a new split, the source unchanged
        val side = if (down) EditorSide.BOTTOM else EditorSide.RIGHT
        return bar.dnd.moveGroupToNewSplit(unit, bar.dnd, side, bar.dnd.freezeOwn(), tabBar.selectedFile(split), null, true)
    }

    /** See [TabBar.placeEmptyGroup]. */
    fun placeEmptyGroup(split: SplitHandle, groupId: String, index: Int) {
        ThreadingAssertions.assertEventDispatchThread()
        if (tabBar.host == null || split.window.isDisposed) return
        registry.barFor(split)?.adapter?.placeEmptyAt(groupId, index)
    }

    /** See [TabBar.openInOrder]. */
    fun openInOrder(split: SplitHandle?, toOpen: List<VirtualFile>, order: List<VirtualFile>): OpenedBlock? {
        if (tabBar.host == null) return null
        var target = split?.window?.takeIf { !it.isDisposed }
        var rest = toOpen
        var first: VirtualFile? = null
        if (split == null) {
            first = toOpen.firstOrNull() ?: return null
            // synchronous (a navigate would open later, after the caller has gone on); a null window lets the platform make one
            FileEditorManagerEx.getInstanceEx(project).openFile(first, null, PlatformInternals.backgroundOpenOptions())
            target = FileEditorManagerEx.getInstanceEx(project).currentWindow?.takeIf { !it.isDisposed }
                ?: FileEditorManagerEx.getInstanceEx(project).windows.firstOrNull { !it.isDisposed }
            rest = toOpen - first
            registry.sync()
        }
        val bar = target?.let { registry.barFor(SplitHandle(it)) } ?: return null
        val opened = GroupReopen.openInOrder(bar, rest, order)
        val files = toOpen.filter { it == first || it in opened }
        return OpenedBlock(bar.split, files)
    }

    /** See [TabBar.moveGroup]. */
    fun moveGroup(from: SplitHandle, groupId: String, to: SplitHandle): Boolean {
        if (tabBar.host == null || from == to) return false
        val source = registry.barFor(from) ?: return false
        val target = registry.barFor(to) ?: return false
        // an empty instance (no tab, its pill alone) moves too, to the end of the target's strip
        val unit = source.dnd.buildUnits().firstOrNull { it.groupId == groupId && (it.keys.isNotEmpty() || it.isEmptyPill) } ?: return false
        val targetUnits = target.dnd.buildUnits()
        source.dnd.moveGroupToSplit(unit, target.dnd, targetUnits, targetUnits.size, selectedBefore = null, focus = false)
        return true
    }
}
