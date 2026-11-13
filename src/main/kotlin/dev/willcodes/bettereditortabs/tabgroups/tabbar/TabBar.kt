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
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripUnit
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
 *                      close scope (IDE-4), split keys and matching, the stored form of the groups and the startup restore rules
 *                      (model/persist, PERS-1, PERS-3). Unit tested without the platform.
 *   tabbar/            the ONLY package that touches IDE-internal APIs (EditorWindow, JBTabsImpl, TabLabel, TabInfo, ...).
 *     TabBar           this facade (project service)          TabBarHost    the contract the service layer implements
 *     SplitHandle, GroupDecoration, HeaderRef, StripStyle     the public data types
 *     strip/           one adapter per split: collapse (hidden tabs) and its animation, pills hosted in tab labels, tint, drop slot,
 *                      empty state
 *     drag/            tab drag and group drag inside the strip, hand-over to the platform's drag-out, moving groups between splits
 *     ide/             hooks into built-in behaviour: close actions (IDE-4), Reopen Closed Tab (LIFE-5), tab limit (IDE-3),
 *                      new-tab placement (MEM-2), closing on behalf of group operations
 *     splits/          restart-stable split identity (PERS-3) and the "splits restored" signal
 *     platform/        PlatformInternals: every reflective access, documented one by one, with a start-up self-check
 *   service/, ui/, actions/, rules/, settings/             callers: they may import only the public types of tabbar/ (and splits/)
 *     service/         TabGroupsService (state + operations, the TabBarHost, and the project's PersistentStateComponent in the
 *                      workspace file: GroupsPersistence converts the model to XML beans, the listeners restore at startup, save before
 *                      close and follow renames and moves), TabGroupsSettings; reaches popups and dialogs only through its
 *                      TabGroupsUi interface, which ui/ implements (ui/ -> service/, never the other way round)
 *     ui/popup/        the group popup (§6): GroupPopup (open, place, close), GroupPopupContent (sections), PopupPlacement;
 *                      TabListPopup, the hover list of a collapsed group (INT-5)
 *     actions/         -> service/ and ui/
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
 * # Persistence (PERS-1, PERS-3)
 *
 * The tab bar stores nothing. The host asks [logicalOrder] for each split it saves (the order including hidden tabs of collapsed groups
 * at their headers), and at startup, once the `splits/` signal says the layout is restored, re-orders each split with [reorder] and
 * applies its groups with `refresh(split, animate = false)`: restoring is never animated (COLL-7).
 *
 * # Collapse animation (COLL-7)
 *
 * [refresh] passes `animate` (and [StripStyle.animateCollapse], SET-10) down to the split's adapter; every other caller of
 * `TabBarAdapter.setGroups` (live re-ordering during drags, group moves) passes false, which snaps a running animation to its end. One
 * `strip/CollapseAnimator` per strip animates Top / Bottom strips by setting the member labels' preferred widths each frame, so the
 * platform's own layouts slide the following tabs (single and multi row); the tween is pure (`model/strip/CollapseAnimation`). While a
 * group collapses the adapter's `shown` view treats it as expanded: the selection leaves the group at the start (COLL-2), the tabs are
 * hidden (`TabInfo.isHidden`) only when the animation ends; an expand shows them at the start and grows them. A flip during the
 * animation reverses it from its current extent; selecting a tab of a collapsing group is reported as COLL-4, so the host expands it.
 * Drags call `TabBarAdapter.finishCollapseAnimations` before they read the strip, and drag results refresh without animation. Left /
 * Right strips animate heights: the platform fixes label heights there, so `strip/VerticalRows` draws animating labels shorter after
 * the layout and moves the labels behind them up.
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

    /**
     * How headers and tinted tabs look (SET-1, SET-2); [StripStyle.DEFAULT] until the host sets it. Setting it re-applies the style to every
     * split at once: headers re-measure (the name is cut at the new length, the pill's reserved width changes, the strip is laid out
     * again) and repaint, tints repaint with the new opacity. Works with or without a host. EDT only.
     */
    var style: StripStyle = StripStyle.DEFAULT
        set(value) {
            ThreadingAssertions.assertEventDispatchThread()
            if (field == value) return
            field = value
            for (bar in bars.values.toList()) bar.adapter.restyle()
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

    /**
     * Pulls [TabBarHost.groupsIn] for [split] and applies it to the strip (headers, collapse, tint). No-op without a host.
     *
     * COLL-7: a group whose collapsed flag changed since the last refresh collapses or expands with an animation when [animate] is
     * true and [style] allows it ([StripStyle.animateCollapse], SET-10); a change during a running animation reverses it from where it
     * is. Selection changes (COLL-2, COLL-3, COLL-4) happen at once, never at the end of the animation. Pass false where nothing may
     * move (restoring groups at startup, a placement change).
     */
    fun refresh(split: SplitHandle, animate: Boolean = true) {
        val h = host ?: return
        val bar = barFor(split) ?: return
        bar.apply(h.groupsIn(split), animate && style.animateCollapse)
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

    /**
     * The header of group [groupId] in [split] if it is currently showing (it has a visible area in the strip), else null (unknown group,
     * scrolled out, no host). Validates the tab component first, so it may be called right after [refresh]: a header is placed in the
     * layout pass that follows a change, and this call runs that pass now. (A strip that has no size yet, because it is not displayed,
     * has nothing to place and answers null.) For anchoring a popup (CTX-1).
     */
    fun header(split: SplitHandle, groupId: String): HeaderRef? {
        if (host == null) return null
        val component = bars[split.window]?.adapter?.showingHeader(groupId) ?: return null
        return HeaderRef(split, groupId, component, component.pillBounds())
    }

    /**
     * The ids of the groups of [split] in the order their headers stand in the strip (collapsed groups at their place), as laid out
     * from the groups last applied. [TabBarHost.groupsIn] must list a split's groups in this order: where two pills share a host tab
     * (collapsed groups next to each other or in front of another group) their order comes from that list. A host that adds a group
     * by other means than a drag (which reports the new order itself) calls [refresh], reads this and refreshes again if its own
     * order differs. Groups without a pill in the strip (no tab at all) are left out. Empty without a host.
     */
    fun groupOrder(split: SplitHandle): List<String> {
        if (host == null) return emptyList()
        val adapter = bars[split.window]?.adapter ?: return emptyList()
        return adapter.stripUnits().mapNotNull { (it as? StripUnit.Pill)?.groupId }.distinct()
    }

    // ---- splits and tabs --------------------------------------------------------------------------------------------------

    /** Every editor split of the project (main area and detached windows). */
    fun splits(): List<SplitHandle> = FileEditorManagerEx.getInstanceEx(project).windows.map { SplitHandle(it) }

    /** The split an action was invoked in: the tab bar the event comes from, else the current split. */
    fun splitFor(e: AnActionEvent): SplitHandle? {
        val window = e.getData(EditorWindow.DATA_KEY) ?: FileEditorManagerEx.getInstanceEx(project).currentWindow
        return window?.let { SplitHandle(it) }
    }

    /**
     * Selects the tab of [file] in [split] (and focuses its editor if [focus]), as clicking the tab would. A tab hidden by a collapsed
     * group is selected too; the strip then reports it as COLL-4 ([TabBarHost.hiddenTabSelected]), so a caller that has just expanded
     * the group itself (INT-5) should expand first and select afterwards. No-op if [file] is not open in [split].
     */
    fun selectTab(split: SplitHandle, file: VirtualFile, focus: Boolean) {
        if (split.window.isDisposed) return
        if (TabClose.openTabs(split.window, listOf(file)).isEmpty()) return
        split.window.setSelectedComposite(file, focus)
    }

    /** Files of the tabs currently shown in [split]'s strip, in order (tabs hidden by collapsed groups excluded). */
    fun visibleFiles(split: SplitHandle): List<VirtualFile> = StripReader.visibleFiles(StripReader.tabsOf(split.window))

    /**
     * Every open tab of [split] in the order the user sees it: [visibleFiles], with the tabs hidden by each collapsed group put at the
     * place of the group's header (PERS-1: this is the order to store, because the platform keeps hidden tabs at the end of its own list
     * and would reopen them there). Built from the adapter's own bookkeeping, so it needs no laid-out strip; where two collapsed groups
     * stand together it agrees with [groupOrder]. Without a host (or an adapter) nothing is hidden and it is the platform's tab order.
     */
    fun logicalOrder(split: SplitHandle): List<VirtualFile> {
        if (split.window.isDisposed) return emptyList()
        val adapter = if (host == null) null else bars[split.window]?.adapter
        return adapter?.logicalOrder() ?: StripReader.allFiles(StripReader.tabsOf(split.window))
    }

    /** The selected file of [split], or null if nothing is selected / every tab is hidden. */
    fun selectedFile(split: SplitHandle): VirtualFile? = StripReader.selectedFile(StripReader.tabsOf(split.window))

    /**
     * Re-orders the visible tabs of [split] to [visibleOrder] (menu operations, e.g. putting a tab next to its new group). Tabs hidden by
     * collapsed groups are not part of the order: they keep their places (behind the visible tabs, each collapsed group keeping its
     * header position), so pass only what [visibleFiles] returns. Files of [visibleOrder] that are not visible are ignored; visible
     * tabs it omits follow the listed ones in their current order. The platform may still put pinned tabs first (see
     * [TabBarHost.tabPinned]). Needs the split's adapter, i.e. an attached [host]; without one it is a no-op, so that an inert plugin
     * never touches the tab order. Call [refresh] afterwards if the groups' member order changed.
     */
    fun reorder(split: SplitHandle, visibleOrder: List<VirtualFile>) {
        if (host == null) return
        bars[split.window]?.adapter?.reorderVisible(visibleOrder)
    }

    /** True if [file] has a pinned tab in [split]; false if it is not open there. */
    fun isPinned(split: SplitHandle, file: VirtualFile): Boolean {
        if (split.window.isDisposed) return false
        return split.window.getComposite(file)?.isPinned ?: false
    }

    /** True if [file]'s tab in [split] is a preview tab (italic title, replaced by the next single-click open); false if it is not open there. */
    fun isPreview(split: SplitHandle, file: VirtualFile): Boolean {
        if (split.window.isDisposed) return false
        return split.window.getComposite(file)?.isPreview ?: false
    }

    /**
     * Turns the preview tab of [file] in [split] into a normal tab, as a double click on it does (`doProcessDoubleClick` in
     * `EditorTabbedContainer`: `composite.isPreview = false`, then a refresh of the tab's colours and title). The platform refreshes the
     * presentation through `scheduleUpdateFileColor`, which a plugin cannot call, so this uses the public
     * `FileEditorManagerEx.updateFileColor`, which schedules the same update for every split showing the file (it ends in `applyTabColor`,
     * which draws the italic title only while the composite is a preview). The refresh is asynchronous. No-op if the tab is not a preview
     * tab or not open there. Each split has its own composite, so other splits showing the file are unaffected.
     */
    fun makePermanent(split: SplitHandle, file: VirtualFile) {
        if (split.window.isDisposed) return
        val composite = split.window.getComposite(file) ?: return
        if (!composite.isPreview) return
        composite.isPreview = false
        FileEditorManagerEx.getInstanceEx(project).updateFileColor(file)
    }

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

    /**
     * [file] was pinned or unpinned somewhere (`FileEditorManagerListener.filePinStateChanged` names no window). MEM-3: tells the host
     * about every split in which the groups last applied list the file as a member and the file now has a pinned tab. No-op without a host.
     */
    internal fun filePinStateChanged(file: VirtualFile) {
        val h = host ?: return
        for (bar in bars.values.toList()) {
            if (bar.groupIdOf(file) == null) continue
            if (!isPinned(bar.split, file)) continue
            h.tabPinned(bar.split, file)
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
