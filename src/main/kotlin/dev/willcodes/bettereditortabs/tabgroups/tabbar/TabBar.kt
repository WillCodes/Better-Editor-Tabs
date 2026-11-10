package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.ThreadingAssertions
import dev.willcodes.bettereditortabs.tabgroups.tabbar.drag.TabBarDnd
import dev.willcodes.bettereditortabs.tabgroups.tabbar.ide.TabClose
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.TabBarAdapter
import java.util.concurrent.ConcurrentHashMap

/**
 * The tab bar facade: the one object the rest of the plugin uses to put groups into the editor tab bars and to hear about gestures.
 *
 * # Architecture
 *
 * ```
 * tabgroups/
 *   model/             pure Kotlin, no com.intellij import: drop rules, slot selection, pill hosting, order and placement rules,
 *                      close scope (IDE-4), split keys and matching. Unit tested without the platform.
 *   tabbar/            the ONLY package that touches IDE-internal APIs (EditorWindow, JBTabsImpl, TabLabel, TabInfo, ...).
 *     TabBar           this facade (project service)          TabBarHost    the contract the service layer implements
 *     SplitHandle, GroupDecoration, HeaderRef                 the public data types
 *     strip/           one adapter per split: collapse (hidden tabs), pills hosted in tab labels, tint, drop slot, empty state
 *     drag/            tab drag and group drag inside the strip, hand-over to the platform's drag-out, moving groups between splits
 *     ide/             hooks into built-in behaviour: close actions (IDE-4), Reopen Closed Tab (LIFE-5), tab limit (IDE-3),
 *                      new-tab placement (MEM-2), closing on behalf of group operations
 *     splits/          restart-stable split identity (PERS-3) and the "splits restored" signal
 *     platform/        PlatformInternals: every reflective access, documented one by one, with a start-up self-check
 *   service/, ui/, actions/, rules/, settings/             callers: they may import only the public types of tabbar/ (and splits/)
 * ```
 *
 * Rules:
 * - Only `tabbar/` touches internal platform API, and inside it only the sub-packages above do; everything in `tabbar/` except the
 *   public types listed in this KDoc, the split-identity types of `splits/` and the classes `plugin.xml` instantiates is `internal`.
 * - Reflection and access by class name live in `platform/PlatformInternals.kt` and nowhere else. Each hook fails soft and is listed
 *   by `PlatformInternals.selfCheck()`, which is logged once per IDE session when this service is created.
 *
 * # How a host plugs in
 *
 * A host implements [TabBarHost] and sets [host]. From then on this service owns one adapter, one drag layer and one MEM-2 placement
 * per editor split (created when a split appears, disposed with it): tab and group drag and drop work in every split without further
 * setup. The host answers [TabBarHost.groupsIn] with the groups of a split whenever it calls [refresh], and receives header gestures,
 * drag results, COLL-4 and MEM-1 events. With [host] null the plugin is completely inert: no component is added to any tab bar, no
 * drag is taken over, and the built-in actions behave exactly like the platform's.
 *
 * # Threading
 *
 * Everything runs on the EDT (Swing state). The one exception is [groupIdOf], which the tab-limit handler may call off the EDT: it
 * answers from an immutable index that is swapped as a whole every time the groups of a split are applied. [TabBarHost.canReopenClosedGroup]
 * is likewise called from action updates and must be thread-safe.
 *
 * # Disposal chain
 *
 * `Project` -> [TabBar] -> `SplitBar` (per split) -> `TabBarAdapter` -> `TabBarDnd` (-> drag controller, drag overlay, MEM-2 placement,
 * glass-pane observers) and the adapter's own collaborators (tint, listeners). A split's chain is disposed when its window goes away
 * and when [host] is detached; disposing the adapter shows hidden tabs again and removes every component it added to the tab bar.
 */
@Service(Service.Level.PROJECT)
class TabBar(private val project: Project) : Disposable {
    private val bars = ConcurrentHashMap<EditorWindow, SplitBar>()

    /**
     * The host the tab bar reports to and pulls groups from; null = the plugin is inert. Setting it attaches (every current split gets
     * its adapter, drag layer and placement, and [TabBarHost.splitAppeared] is called for each) or detaches (all of that is
     * disposed; the host gets no callbacks for it). EDT only.
     */
    @Volatile
    var host: TabBarHost? = null
        set(value) {
            ThreadingAssertions.assertEventDispatchThread()
            if (field === value) return
            if (field != null) detach()
            field = value
            if (value != null) sync()
        }

    init {
        PlatformInternals.logSelfCheckOnce()
        // Programmatic subscription: no plugin.xml entry needed.
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.Before.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener.Before {
                override fun beforeFileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (bars.isEmpty()) return
                    for (bar in bars.values.toList()) bar.adapter.beforeFileClosed(file)
                }
            },
        )
    }

    // ---- groups --------------------------------------------------------------------------------------------------------

    /** Pulls [TabBarHost.groupsIn] for [split] and applies it to the strip (headers, collapse, tint). No-op without a host. */
    fun refresh(split: SplitHandle) {
        val h = host ?: return
        val bar = barFor(split) ?: return
        bar.apply(h.groupsIn(split))
    }

    /** [refresh] for every split. */
    fun refreshAll() {
        if (host == null) return
        sync()
        for (bar in bars.values.toList()) refresh(bar.split)
    }

    /**
     * The id of the group [file] belongs to in [split], per the groups last applied there; null if ungrouped or without a host. Used
     * by the IDE hooks. Safe to call from any thread.
     */
    fun groupIdOf(split: SplitHandle, file: VirtualFile): String? {
        if (host == null) return null
        return bars[split.window]?.groupIdOf(file)
    }

    // ---- splits and tabs --------------------------------------------------------------------------------------------------

    /** Every editor split of the project (main area and detached windows). */
    fun splits(): List<SplitHandle> = FileEditorManagerEx.getInstanceEx(project).windows.map { SplitHandle(it) }

    /** The split an action was invoked in: the tab bar the event comes from, else the current split. */
    fun splitFor(e: AnActionEvent): SplitHandle? {
        val window = e.getData(EditorWindow.DATA_KEY) ?: FileEditorManagerEx.getInstanceEx(project).currentWindow
        return window?.let { SplitHandle(it) }
    }

    /** Files of the tabs currently shown in [split]'s strip, in order (tabs hidden by collapsed groups excluded). */
    fun visibleFiles(split: SplitHandle): List<VirtualFile> = StripReader.visibleFiles(StripReader.tabsOf(split.window))

    /** The selected file of [split], or null if nothing is selected / every tab is hidden. */
    fun selectedFile(split: SplitHandle): VirtualFile? = StripReader.selectedFile(StripReader.tabsOf(split.window))

    /** The right-clicked tab if a tab context menu is showing in [split], otherwise the selected tab. */
    fun contextFile(split: SplitHandle): VirtualFile? = StripReader.contextFile(split.window)

    /** The subset of [files] that is open in [split] (tabs hidden by collapsed groups included), in the given order. */
    fun openTabs(split: SplitHandle, files: List<VirtualFile>): List<VirtualFile> = TabClose.openTabs(split.window, files)

    /** Closes [files] in [split] the normal way: each one is added to Reopen Closed Tab history. */
    fun closeTabs(split: SplitHandle, files: List<VirtualFile>) = TabClose.closeTabs(split.window, files)

    // ---- internal ---------------------------------------------------------------------------------------------------------

    /** The strip adapter of [split] (null without a host or for an unknown split). Tests and the sub-packages only. */
    internal fun adapterOf(split: SplitHandle): TabBarAdapter? = bars[split.window]?.adapter

    /** The drag layers of every split, for group drags that end in another split. */
    internal fun dragLayers(): List<TabBarDnd> = bars.values.map { it.dnd }

    /**
     * Brings the per-split state in line with the editor windows: repins hidden tabs, disposes what belongs to closed splits and
     * creates what new splits need. Called on every file open / close / selection change and once at start-up (see
     * [TabBarEditorListener]); new splits always open a file, closed ones are `EditorWindow.isDisposed`. No-op without a host.
     */
    internal fun sync() {
        val h = host ?: return
        bars.values.forEach { it.adapter.repinHidden() }
        val current = FileEditorManagerEx.getInstanceEx(project).windows.toSet()
        for ((w, bar) in bars.entries.toList()) {
            if (w !in current || w.isDisposed) {
                bars.remove(w)
                Disposer.dispose(bar)
                h.splitDisposed(SplitHandle(w))
            }
        }
        for (w in current) if (!w.isDisposed) ensureBar(w)
    }

    /**
     * [file] was closed in some split. MEM-1: tells the host about every split whose applied groups list the file although it has no
     * tab there any more. The close of the tabs of a group that is being moved to another split is not reported for the source
     * split, because the source has been told about its new groups first and its index no longer lists the file.
     */
    internal fun fileClosed(file: VirtualFile) {
        sync()
        val h = host ?: return
        for (bar in bars.values.toList()) {
            if (bar.groupIdOf(file) == null) continue
            if (TabClose.openTabs(bar.split.window, listOf(file)).isNotEmpty()) continue // still open in this split
            h.tabClosed(bar.split, file)
        }
    }

    private fun barFor(split: SplitHandle): SplitBar? {
        val w = split.window
        if (w.isDisposed) return null
        return bars[w] ?: ensureBar(w)
    }

    /** The bar of [window], created (and announced to the host) if the split is new. */
    private fun ensureBar(window: EditorWindow): SplitBar {
        bars[window]?.let { return it }
        val bar = SplitBar(SplitHandle(window), this, this)
        bars[window] = bar
        host?.splitAppeared(bar.split)
        return bar
    }

    private fun detach() {
        val all = bars.values.toList()
        bars.clear()
        all.forEach { Disposer.dispose(it) }
    }

    override fun dispose() {
        detach()
    }
}
