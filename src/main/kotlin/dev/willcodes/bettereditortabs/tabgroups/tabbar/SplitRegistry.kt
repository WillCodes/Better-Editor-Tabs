package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.splits.SplitRecency
import dev.willcodes.bettereditortabs.tabgroups.tabbar.drag.TabBarDnd
import dev.willcodes.bettereditortabs.tabgroups.tabbar.ide.TabClose
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.TemporarySplits
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

/**
 * The per-split state of [TabBar]: one [SplitBar] per editor split (SPLIT-2), created when a split appears and disposed with it, the
 * focus history that orders the splits ([TabBar.recentSplits]), and the per-bar dispatch of file events (MEM-1 close, MEM-3 pin).
 * The bars are children of [parent] in the disposal chain (`Project` -> [TabBar] -> `SplitBar`); [onBarCreated] is the hook for what a
 * new bar needs besides its own set-up (the rule preview, POP-4). EDT only, like the facade.
 */
internal class SplitRegistry(
    private val project: Project,
    private val tabBar: TabBar,
    private val parent: Disposable,
    private val temporary: TemporarySplits,
    private val host: () -> TabBarHost?,
    private val onBarCreated: (SplitBar) -> Unit,
) {
    private val bars = ConcurrentHashMap<EditorWindow, SplitBar>()

    /** Splits in the order they were last seen as the current window, most recent first (see [recentSplits]); dead ones are pruned on the way. */
    private var focusHistory: List<EditorWindow> = emptyList()

    /** True while the host's setter creates the bars of the splits that exist already (they must not report their files as opened). */
    var attaching = false

    operator fun get(window: EditorWindow): SplitBar? = bars[window]

    fun isEmpty(): Boolean = bars.isEmpty()

    /** The split bars that exist now. */
    fun bars(): List<SplitBar> = bars.values.toList()

    /** The drag layers of every split, for group drags that end in another split. */
    fun dragLayers(): List<TabBarDnd> = bars.values.map { it.dnd }

    /** The editor windows of the project (every split, temporary ones included). */
    fun windows(): List<EditorWindow> = FileEditorManagerEx.getInstanceEx(project).windows.toList()

    /** The split bar of [window] while a host is attached (null otherwise): the overflow list (COLL-6) reads its groups and reports picks. */
    fun splitBarOf(window: EditorWindow): SplitBar? = if (host() == null) null else bars[window]

    fun barFor(split: SplitHandle): SplitBar? {
        val w = split.window
        if (w.isDisposed) return null
        return bars[w] ?: ensureBar(w)
    }

    /** The bar of [window], created (and announced to the host) if the split is new. */
    private fun ensureBar(window: EditorWindow): SplitBar {
        bars[window]?.let { return it }
        val bar = SplitBar(SplitHandle(window), tabBar, parent, reportInitial = !attaching)
        bars[window] = bar
        onBarCreated(bar)
        host()?.splitAppeared(bar.split)
        return bar
    }

    /** Disposes every bar (the host is detached or the service goes). */
    fun detach() {
        val all = bars.values.toList()
        bars.clear()
        all.forEach { Disposer.dispose(it) }
    }

    /** See [TabBar.recentSplits]. */
    fun recentSplits(): List<SplitHandle> {
        noteCurrentWindow()
        val live = FileEditorManagerEx.getInstanceEx(project).windows.filter { !it.isDisposed }
        return SplitRecency.order(live, focusHistory).map { SplitHandle(it) }
    }

    private fun noteCurrentWindow() {
        val current = FileEditorManagerEx.getInstanceEx(project).currentWindow ?: return
        if (current.isDisposed) return
        focusHistory = SplitRecency.touch(focusHistory.filter { !it.isDisposed }, current)
    }

    /** See [TabBar.sync]. */
    fun sync() {
        val h = host() ?: return
        noteCurrentWindow()
        bars.values.forEach { it.adapter.repinHidden() }
        val current = FileEditorManagerEx.getInstanceEx(project).windows.toSet()
        for ((w, bar) in bars.entries.toList()) {
            if (w !in current || w.isDisposed) {
                bars.remove(w)
                temporary.windowGone(w, bar) // SPLIT-6: a temporary split that went without its placeholder being closed
                Disposer.dispose(bar)
                // An unsplit adds the tabs of the merged-away split to the surviving one in the same event, and that split's report of them
                // (SPLIT-2) is queued: the host must still know the disposed split's groups when it gets it, so it hears of the disposal after.
                if (bars.values.any { it.hasPendingReport }) {
                    SwingUtilities.invokeLater { if (host() === h) h.splitDisposed(SplitHandle(w)) }
                }
                else h.splitDisposed(SplitHandle(w))
            }
        }
        for (w in current) if (!w.isDisposed) ensureBar(w)
        temporary.settle()
    }

    /** See [TabBar.fileClosed]. */
    fun fileClosed(file: VirtualFile) {
        temporary.fileClosed(file)
        sync()
        val h = host() ?: return
        for (bar in bars.values.toList()) {
            if (bar.groupIdOf(file) == null) continue
            if (TabClose.openTabs(bar.split.window, listOf(file)).isNotEmpty()) continue // still open in this split
            h.tabClosed(bar.split, file)
        }
    }

    /** See [TabBar.filePinStateChanged]. */
    fun filePinStateChanged(file: VirtualFile) {
        val h = host() ?: return
        for (bar in bars.values.toList()) {
            if (TabClose.openTabs(bar.split.window, listOf(file)).isNotEmpty()) bar.adapter.pinChanged() // no group shifts (see pinChanged)
            bar.adapter.recheckSort() // IDE-2: pinned tabs sort first
        }
        for (bar in bars.values.toList()) {
            if (bar.groupIdOf(file) == null) continue
            if (!tabBar.isPinned(bar.split, file)) continue
            h.tabPinned(bar.split, file)
        }
    }
}
