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
 * - `MouseDragHelper` ([HeaderDragHelper]) for dragging a whole group by its header.
 * - `EditorWindow` / `FileEditorManagerEx.openFile` / `FileEditorOpenOptions` to move files between splits (see [moveGroupToSplit], [moveTabToSplit]).
 *
 * This class is the hub of the drag layer: it holds the pieces of one split and the geometry helpers they share.
 * [peers] lists the drag layers of every split of the project (group and tab drags can continue in and end in another split).
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

    /**
     * The collapsed (or partly shown) groups of this strip that a tab drag expanded by a hold
     * behind them. They stay expanded for the rest of the drag and after it, whatever the drop ([commitExpandedByDrag]).
     */
    val expandedByDrag = LinkedHashSet<String>()

    /**
     * The groups a tab drag expanded here ([expandedByDrag]) are expanded for good: the host learns it through the drag result
     * path (membership and order unchanged). Called when the drag ends, whatever the outcome; a drop on this strip folds them into its own
     * result first ([TabDragSession.commit]), so this then finds nothing to do.
     */
    fun commitExpandedByDrag() {
        if (expandedByDrag.isEmpty()) return
        val ids = expandedByDrag.toSet()
        expandedByDrag.clear()
        val now = model.groups()
        if (now.none { it.id in ids && it.collapsed }) return
        model.commit(now.map { if (it.id in ids) it.copy(collapsed = false, peek = null) else it })
    }
    val observer = DragObserver(controller)

    /**
     * Set by `SplitBar`: tells this split's linked-file placement that the tabs it has now are not "newly opened" ([runMove] calls it on the
     * target after a move: the files our own drag code opened there already got their membership from the drop, so that placement must not
     * see them as files opened by the user).
     */
    var acknowledgeOpened: () -> Unit = {}
    private var attached = false
    private var disposed = false
    private var headerDrag: HeaderDragHelper? = null

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
        headerDrag = HeaderDragHelper(this, disposable).also { it.start() }
    }

    override fun dispose() {
        disposed = true
    }

    val horizontal: Boolean get() = tabs.isHorizontalTabs

    /**
     * Where the tab component's top-left is on screen, or null while it is not showing. A seam: the headless tests set it, because a
     * component that is not on a real screen has no location and the session code works in screen coordinates.
     */
    internal var origin: () -> Point? = { if (tabs.isShowing) tabs.locationOnScreen else null }

    /** The tab component is on screen (see [origin]). */
    val isShowing: Boolean get() = origin() != null

    /** What [validateNow] does. A seam: a component that is not displayable ignores `validate()`, so the headless tests lay the strip out themselves. */
    internal var validator: () -> Unit = { if (!tabs.isValid) tabs.validate() }

    /** Lays the strip out now if it is invalid (the drag judges the pointer against the strip as it is laid out). */
    fun validateNow() = validator()

    // ---- geometry helpers --------------------------------------------------------------------------------------------

    /**
     * The tab strip in screen coordinates, with a tolerance around it, or null if not showing. Along the strip it is the whole tab
     * component; across it, the extent of the rows (the platform's header rectangle and every unit that is on screen), so a header
     * rectangle that does not cover the whole strip (it ends at the last tab or the entry point) does not matter.
     */
    fun stripScreenRect(): Rectangle? {
        val origin = origin()
        if (origin == null || tabs.isHideTabs) return null
        val rects = ArrayList<Rectangle>()
        tabs.lastLayoutPass?.headerRectangle?.let { rects += Rectangle(it) }
        for (u in adapter.stripUnits()) rects += u.rect
        // the gap that an arriving item opens in the row after a separate pinned row (nothing but pinned tabs) is part of the strip too
        adapter.drag.foreignGapRect()?.let { rects += it }
        val band = StripDragGeometry.crossBand(rects, horizontal) ?: return null
        val tolerance = JBUI.scale(8)
        val across = band.last - band.first + 1
        val r = if (horizontal) Rectangle(0, band.first - tolerance, tabs.width, across + 2 * tolerance)
        else Rectangle(band.first - tolerance, 0, across + 2 * tolerance, tabs.height)
        r.translate(origin.x, origin.y)
        return r
    }

    fun toTabs(screen: Point): Point {
        val origin = origin() ?: return Point(screen).also { SwingUtilities.convertPointFromScreen(it, tabs) }
        return Point(screen.x - origin.x, screen.y - origin.y)
    }

    /** The header at [tabsPoint], or the header of the group whose count pill is there. */
    fun headerAt(tabsPoint: Point): GroupHeader? = adapter.headerAt(tabsPoint)

    /** The file of the visible tab label under [tabsPoint], or null. */
    fun tabFileAt(tabsPoint: Point): VirtualFile? {
        for (info in tabs.getVisibleInfos()) {
            val label = tabs.getTabLabel(info) ?: continue
            if (!label.bounds.isEmpty && label.bounds.contains(tabsPoint)) return info.`object` as? VirtualFile
        }
        return null
    }

    /** True while a tab drag of ours is running or the platform is dragging a tab of this strip. */
    val isTabDragActive: Boolean get() = controller.isActive || adapter.drag.isPlatformTabDragActive()

    /** [isTabDragActive], or a group drag of ours is running (the alphabetical sort never re-orders the strip then). */
    val isDragActive: Boolean get() = isTabDragActive || headerDrag?.isDragging == true

    /**
     * The drop units of this split in display order (one per ungrouped tab, one per group instance; a collapsed group is its pill
     * alone), with bounds in tab-bar coordinates, built from [TabBarAdapter.stripUnits] (tab rectangles exclude the pill space
     * reserved in a label, so a pill and its neighbours are separate units with separate edges).
     */
    fun buildUnits(): List<MoveUnit<VirtualFile>> {
        val strip = adapter.stripUnits()
        val groups = model.groups().associateBy { it.id }
        val memberTabs = strip.filterIsInstance<StripUnit.Tab>().filter { it.groupId != null }.groupBy { it.groupId!! }
        val countsOf = strip.filterIsInstance<StripUnit.Count>().groupBy { it.groupId }
        val open by lazy(LazyThreadSafetyMode.NONE) { StripReader.allFiles(tabs).toHashSet() }
        val units = ArrayList<MoveUnit<VirtualFile>>()
        for (u in strip) {
            when (u) {
                is StripUnit.Tab -> if (u.groupId == null) units += MoveUnit(null, listOf(u.file), Rectangle(u.rect), u.pinned)
                is StripUnit.Count -> {}
                is StripUnit.Pill -> {
                    val group = groups[u.groupId]
                    // the peek's tab and the count of a partly shown group are part of its unit
                    val shown = memberTabs[u.groupId].orEmpty()
                    val counts = countsOf[u.groupId].orEmpty()
                    val rect = Rectangle(u.rect)
                    for (t in shown) if (!t.rect.isEmpty) rect.add(t.rect)
                    for (c in counts) if (!c.rect.isEmpty) rect.add(c.rect)
                    // a collapsed group's members are hidden tabs: they come from the model (the ones that are open: an empty group's
                    // pill is a collapsed unit with no key at all)
                    val keys = if (u.collapsed) group?.members.orEmpty().filter { it in open } else shown.map { it.file }
                    val rects = (listOf(u.rect) + shown.map { it.rect } + counts.map { it.rect }).filter { !it.isEmpty }
                    val runs = GapRuns.merge(rects, horizontal).ifEmpty { listOf(rect) }
                    units += MoveUnit(u.groupId, keys, rect, false, runs.first(), Rectangle(u.rect), u.collapsed, runs)
                }
            }
        }
        return units
    }

    /** The length of one tab along this strip (a row's height in a side strip, a tab's width otherwise); 0 if no tab is on screen. */
    fun rowLength(): Int {
        val r = adapter.stripUnits().filterIsInstance<StripUnit.Tab>().firstOrNull { !it.rect.isEmpty }?.rect ?: return 0
        return if (horizontal) r.width else r.height
    }

    /**
     * The group drag re-ordered the strip live; [flat] is the order it shows. Commits it: every group keeps its members, in that
     * order ([groups] as they were when the drag began, collapsed flags unchanged). [expanding]: the group that was dragged as its
     * collapsed header and comes back expanded; it plays the expand animation ([SplitGroups.commit]).
     */
    fun commitGroupOrder(groups: List<GroupDecoration>, flat: List<VirtualFile>, expanding: String? = null) {
        model.commit(groups.map { g ->
            val members = g.members.toSet()
            g.copy(members = flat.filter { it in members })
        }, expanding)
        LOG.info("group move committed: ${groups.map { it.id }}")
    }
}
