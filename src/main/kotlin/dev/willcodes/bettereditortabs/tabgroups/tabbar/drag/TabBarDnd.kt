package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeGlassPaneUtil
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.model.strip.GapRuns
import dev.willcodes.bettereditortabs.tabgroups.model.strip.MoveUnit
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripDragGeometry
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.GroupHeader
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripUnit
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.TabBarAdapter
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import javax.swing.SwingUtilities

private val LOG = logger<TabBarDnd>()

/**
 * Drag-and-drop support for one split's editor tab bar, installed by the `TabBar` service for every split while a host is attached.
 * Disposed with the [TabBarAdapter].
 *
 * Internal APIs wrapped, and why:
 * - `JBTabsImpl` / `DragHelper` / `TabInfo.dragOutDelegate`: tab drags inside the strip are run by [TabDragController] instead of
 *   the platform's `DragHelper` (which only swaps labels and knows nothing about pills); when the pointer leaves the strip the
 *   drag is handed over to the platform's drag-out ([DragHandOff]).
 * - `IdeGlassPane` preprocessors ([DragObserver], weight 1, ahead of `DragHelper`'s 2) to see and consume the drag events.
 * - `MouseDragHelper` ([HeaderDragHelper]) for dragging a whole group by its header (INT-6).
 * - `EditorWindow` / `FileEditorManagerEx.openFile` / `FileEditorOpenOptions` to move files between splits (see [moveGroupToSplit]).
 *
 * This class is the hub of the drag layer: it holds the pieces of one split and the geometry helpers they share.
 * [peers] lists the drag layers of every split of the project (group drags can end in another split).
 *
 * EDT only.
 */
internal class TabBarDnd(
    val adapter: TabBarAdapter,
    val model: SplitGroups,
    val peers: () -> List<TabBarDnd>,
) : Disposable {
    val tabs: JBEditorTabs = StripReader.tabsOf(adapter.window)
    private val disposable = Disposer.newDisposable("TabBarDnd")
    val overlay = DragOverlay(tabs)
    val reorder = LiveReorder(this)
    val controller = TabDragController(this)
    val observer = DragObserver(controller)
    private var attached = false
    private var disposed = false

    init {
        Disposer.register(adapter, this)
        Disposer.register(this, disposable)
        Disposer.register(disposable) { controller.dispose() }
        Disposer.register(disposable) { overlay.dispose() }
        if (tabs.isShowing) {
            attach()
        }
        else {
            val listener = object : HierarchyListener {
                override fun hierarchyChanged(e: HierarchyEvent) {
                    if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && tabs.isShowing) {
                        tabs.removeHierarchyListener(this)
                        attach()
                    }
                }
            }
            tabs.addHierarchyListener(listener)
            Disposer.register(disposable) { tabs.removeHierarchyListener(listener) }
        }
    }

    private fun attach() {
        if (attached || disposed) return
        attached = true
        val pane = IdeGlassPaneUtil.find(tabs)
        pane.addMousePreprocessor(observer, disposable)
        pane.addMouseMotionPreprocessor(observer, disposable)
        HeaderDragHelper(this, disposable).start()
    }

    override fun dispose() {
        disposed = true
    }

    val horizontal: Boolean get() = tabs.isHorizontalTabs

    // ---- geometry helpers --------------------------------------------------------------------------------------------

    /**
     * The tab strip in screen coordinates, with a tolerance around it, or null if not showing. Along the strip it is the whole tab
     * component; across it, the extent of the rows (the platform's header rectangle and every unit that is on screen), so a header
     * rectangle that does not cover the whole strip (it ends at the last tab or the entry point) does not matter.
     */
    fun stripScreenRect(): Rectangle? {
        if (!tabs.isShowing || tabs.isHideTabs) return null
        val rects = ArrayList<Rectangle>()
        tabs.lastLayoutPass?.headerRectangle?.let { rects += Rectangle(it) }
        for (u in adapter.stripUnits()) rects += u.rect
        val band = StripDragGeometry.crossBand(rects, horizontal) ?: return null
        val tolerance = JBUI.scale(8)
        val across = band.last - band.first + 1
        val r = if (horizontal) Rectangle(0, band.first - tolerance, tabs.width, across + 2 * tolerance)
        else Rectangle(band.first - tolerance, 0, across + 2 * tolerance, tabs.height)
        val origin = tabs.locationOnScreen
        r.translate(origin.x, origin.y)
        return r
    }

    fun toTabs(screen: Point): Point = Point(screen).also { SwingUtilities.convertPointFromScreen(it, tabs) }

    fun headerAt(tabsPoint: Point): GroupHeader? =
        adapter.headerComponents().firstOrNull { !it.bounds.isEmpty && it.bounds.contains(tabsPoint) }

    /** The file of the visible tab label under [tabsPoint], or null. */
    fun tabFileAt(tabsPoint: Point): VirtualFile? {
        for (info in tabs.getVisibleInfos()) {
            val label = tabs.getTabLabel(info) ?: continue
            if (!label.bounds.isEmpty && label.bounds.contains(tabsPoint)) return info.`object` as? VirtualFile
        }
        return null
    }

    /** True while a tab drag of ours is running or the platform is dragging a tab of this strip. */
    val isTabDragActive: Boolean get() = controller.isActive || adapter.isPlatformTabDragActive()

    /**
     * The drop units of this split in display order (one per ungrouped tab, one per group instance; a collapsed group is its pill
     * alone), with bounds in tab-bar coordinates, built from [TabBarAdapter.stripUnits] (tab rectangles exclude the pill space
     * reserved in a label, so a pill and its neighbours are separate units with separate edges).
     */
    fun buildUnits(): List<MoveUnit<VirtualFile>> {
        val strip = adapter.stripUnits()
        val groups = model.groups().associateBy { it.id }
        val memberTabs = strip.filterIsInstance<StripUnit.Tab>().filter { it.groupId != null }.groupBy { it.groupId!! }
        val units = ArrayList<MoveUnit<VirtualFile>>()
        for (u in strip) {
            when (u) {
                is StripUnit.Tab -> if (u.groupId == null) units += MoveUnit(null, listOf(u.file), Rectangle(u.rect), u.pinned)
                is StripUnit.Pill -> {
                    val group = groups[u.groupId]
                    val shown = memberTabs[u.groupId].orEmpty()
                    val rect = Rectangle(u.rect)
                    for (t in shown) if (!t.rect.isEmpty) rect.add(t.rect)
                    // a collapsed group's members are hidden tabs: they come from the model
                    val keys = if (u.collapsed) group?.members.orEmpty() else shown.map { it.file }
                    val rects = (listOf(u.rect) + shown.map { it.rect }).filter { !it.isEmpty }
                    val lead = GapRuns.merge(rects, horizontal).firstOrNull() ?: rect
                    units += MoveUnit(u.groupId, keys, rect, false, lead, Rectangle(u.rect), u.collapsed)
                }
            }
        }
        return units
    }

    /** The natural length along a top / bottom strip of the tabs of [files] in this split: the sum of their labels' preferred widths. */
    fun naturalLength(files: Collection<VirtualFile>): Int {
        val wanted = files.toSet()
        return tabs.getVisibleInfos().filter { it.`object` in wanted }.sumOf { tabs.getTabLabel(it)?.preferredSize?.width ?: 0 }
    }

    /** The length of one tab along this strip (a row's height in a side strip, a tab's width otherwise); 0 if no tab is on screen. */
    fun rowLength(): Int {
        val r = adapter.stripUnits().filterIsInstance<StripUnit.Tab>().firstOrNull { !it.rect.isEmpty }?.rect ?: return 0
        return if (horizontal) r.width else r.height
    }

    /**
     * The group drag re-ordered the strip live; [flat] is the order it shows. Commits it: every group keeps its members, in that
     * order ([groups] as they were when the drag began, collapsed flags unchanged).
     */
    fun commitGroupOrder(groups: List<GroupDecoration>, flat: List<VirtualFile>) {
        model.commit(groups.map { g ->
            val members = g.members.toSet()
            g.copy(members = flat.filter { it in members })
        })
        LOG.info("group move committed: ${groups.map { it.id }}")
    }
}
