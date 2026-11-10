package dev.willcodes.bettereditortabs.tabgroups.tabbar.splits

import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.fileEditor.impl.EditorsSplitters
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.splits.SplitKey
import dev.willcodes.bettereditortabs.tabgroups.model.splits.SplitSnapshot
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals

/** One live editor split with its current restart-stable key. Safe to hand outside `tabbar/`. */
class SplitInfo internal constructor(
    val handle: SplitHandle,
    val key: SplitKey,
    val files: List<VirtualFile>,
    val isDetached: Boolean,
) {
    fun snapshot(): SplitSnapshot = SplitSnapshot(key, files.mapTo(LinkedHashSet()) { it.url })
}

/**
 * Computes restart-stable [SplitKey]s for editor splits and lists all live splits (main area and detached windows).
 *
 * Wraps internal API: `EditorWindow`, `EditorsSplitters` (`owner`, `isFloating`, `manager`), `EditorTabbedContainer.component`,
 * `FileEditorManagerEx.windows`, and the persisted dock-window id ([PlatformInternals.dockWindowId], read by reflection).
 *
 * Why this is needed: the platform gives splits no id. `EditorsSplitters.writeExternal` (FileEditorManager state, main area)
 * and `DockManagerImpl.getState` (one `window` element with an `id` per detached window, each embedding a splitter-tree state)
 * persist only the splitter tree and the file entries. So a key is derived from the tree position and is never stored on the
 * window. Keys must be recomputed on demand (on save, and for startup matching); after a split or unsplit in the session the
 * paths of other splits change, which is expected: the runtime identity during a session is the [SplitHandle].
 *
 * All methods must run on the EDT (they read Swing state).
 */
object SplitIdentity {

    /** Lists every live split of [project]: the main area first, then detached windows, each in tree order. */
    fun allSplits(project: Project): List<SplitInfo> {
        val manager = FileEditorManagerEx.getInstanceEx(project)
        val owners = LinkedHashSet<EditorsSplitters>()
        (manager as? FileEditorManagerImpl)?.let { owners += it.mainSplitters }
        manager.windows.forEach { owners += it.owner }

        val result = ArrayList<SplitInfo>()
        for (owner in owners) {
            val windows = owner.getWindows().sortedWith(compareBy({ pathOf(it) }, { System.identityHashCode(it) }))
            for (window in windows) {
                if (window.isDisposed) continue
                result += SplitInfo(
                    handle = SplitHandle(window),
                    key = keyOf(window),
                    files = window.fileList,
                    isDetached = owner.isFloating,
                )
            }
        }
        return result
    }

    private fun keyOf(window: EditorWindow): SplitKey = SplitKey(containerIdOf(window.owner), pathOf(window))

    /** `"main"` for the main editor area, `"float:<dock window id>"` for a detached window. */
    private fun containerIdOf(owner: EditorsSplitters): String {
        if (!owner.isFloating) return SplitKey.MAIN
        return SplitKey.floating(PlatformInternals.dockWindowId(owner))
    }

    /**
     * Route from the container root to [window] through the splitter tree (see [SplitKey.path]).
     * Walks the Swing parents of the window's tab component up to the owning [EditorsSplitters]; the platform
     * walks the same tree to persist the layout (`EditorsSplitters.kt`, `writePanel`).
     */
    private fun pathOf(window: EditorWindow): String {
        val owner = window.owner
        var child: java.awt.Component = window.tabbedPane.component
        val steps = ArrayList<String>()
        while (true) {
            val parent = child.parent ?: break
            if (parent === owner) break
            if (parent is Splitter) {
                steps += SplitKey.step(vertical = parent.isVertical, first = parent.firstComponent === child)
            }
            child = parent
        }
        steps.reverse()
        return steps.joinToString("")
    }
}
