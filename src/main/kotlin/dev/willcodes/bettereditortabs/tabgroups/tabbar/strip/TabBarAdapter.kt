package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.ui.tabs.impl.TabLabel
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.StripStyle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.Color
import java.awt.Rectangle
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Per-split coordinator around one editor tab bar (`EditorWindow.tabbedPane.tabs`, a `JBEditorTabs`). It owns the tab bar's extra
 * children and wires the collaborators together; each collaborator owns one concern and wraps its own internal API:
 *
 * - [CollapseController]: hiding and showing the tabs of collapsed groups, anchors, saved selection (`TabInfo.isHidden`, `sortTabs`);
 * - [CollapseAnimator]: the collapse / expand animation (COLL-7): while a group collapses its tabs are still visible and the
 *   collaborators see the group as expanded ([shown]); the animator sets the member labels' preferred widths each frame and the
 *   platform lays the strip out (`TabLabel.preferredSize`). The tabs are hidden when it ends;
 * - [PillLayout]: which tab label hosts each pill and the space reserved there ([TabLabelSpace], `TabLabel.border`);
 * - [StripGeometry]: pill areas, border cuts and [StripUnit]s from the labels' bounds;
 * - [PillHeaders]: the header components, their placement and z-order;
 * - [TabTransit]: the dragged tab that is out of this strip while its drag is over another split (hidden, selection handed over); a
 *   dragged group in the same situation is simply not laid out ([setGroupTransit]);
 * - [DragPreview]: previewed membership, dragged file and the drop slot ([DragGap]) during the plugin's own drag;
 * - [TabTint]: the group colour on member tabs (`TabInfo.setTabColor`);
 * - [EmptyEditorOverlay]: the empty state when every tab is hidden;
 * - [SpareRow]: the row for pills beside a separate pinned row when no unpinned tab is left to host them;
 * - [CloseSelectionGuard]: keeps a close from selecting a hidden tab;
 * - [PillAwareTabsBorder]: cuts the pills' slots out of the strip border.
 *
 * Hosting: all extra components (headers, a layout hook, the drop slot, the empty-state overlay) are direct children of the tab
 * component. A header's *space* is part of a tab label: the host label gets a border inset of the pill's length, so the platform's
 * own layouts (single row, scrollable, multi row, side placements, overflow) already count it. After each layout pass
 * [LayoutHook.doLayout], called by `validateTree`, only *places* the pill component over the reserved area. Labels are never moved
 * by us ([dev.willcodes.bettereditortabs.tabgroups.model.strip.PillHosting]).
 *
 * Internal APIs the coordinator itself wraps: `TabsListener` (selection / removal / move events), `JBTabsImpl.doLayout` and
 * `validateTree` (the layout hook), the platform drag state ([PlatformInternals.isTabDragActive]).
 *
 * EDT only.
 */
internal class TabBarAdapter(
    internal val window: EditorWindow,
    parent: Disposable,
    private val style: () -> StripStyle = { StripStyle.DEFAULT },
) : Disposable {
    private val tabs = StripReader.tabsOf(window)
    private val disposable = Disposer.newDisposable("TabBarAdapter")
    private var disposed = false
    private val alive: () -> Boolean = { !disposed && !window.isDisposed }

    /** The real groups, as last set. */
    private var groups: List<GroupDecoration> = emptyList()

    /** The group whose drag is over another split (or no strip): it is out of this strip, no pill and no space ([setGroupTransit]). */
    private var groupInTransit: String? = null

    /**
     * What the strip shows: [groups], except that a group whose collapse is still animating counts as expanded (its tabs are visible,
     * tinted, the pill is hosted by its first tab). Every collaborator reads this one.
     */
    private val shown: List<GroupDecoration>
        get() = if (!animator.isActive) groups else groups.map { if (it.collapsed && animator.isCollapsing(it.id)) it.copy(collapsed = false) else it }

    /** The groups whose pills the strip lays out: [shown] without the group in transit (its tabs stay hidden by [collapse]). */
    private val pillGroups: List<GroupDecoration>
        get() = groupInTransit?.let { id -> shown.filter { it.id != id } } ?: shown

    private val collapse = CollapseController(tabs, window)
    private val preview = DragPreview(tabs)
    private val tint = TabTint(tabs, this, { !window.isDisposed }, style)
    private val headers = PillHeaders(tabs, ::platformDecidesHits, ::pollDragState, ::platformDraggedLabel, style)
    private val transit = TabTransit(tabs, window, collapse)
    private val layout = PillLayout(tabs, alive, { pillGroups }, headers::of, preview, collapse, ::platformDraggedFile) { transit.state }
    private val animator = CollapseAnimator(tabs, layout.space) { if (alive()) own { applyShown() } }
    private val geometry: StripGeometry = StripGeometry(tabs, alive, layout, { pillGroups }, preview, { spareRow.rect }, { verticalRows.rowHeight(it) })
    private val emptyOverlay = EmptyEditorOverlay(tabs, window)
    private val closeGuard = CloseSelectionGuard(tabs, window, collapse)
    private val verticalRows: VerticalRows = VerticalRows(tabs, layout, geometry, animator::verticalExtentOf)
    private val spareRow = SpareRow(tabs, layout)
    private val hook = LayoutHook()

    private var layingOutAgain = false
    private var dragWasActive = false

    /** Whether the strip is on screen (an animation of a strip nobody sees is skipped); a test hook. */
    internal var isShowing: () -> Boolean = { tabs.component.isShowing }

    /** The animator (tests only: drive frames with [CollapseAnimator.tick] and an injected clock). */
    internal val collapseAnimator: CollapseAnimator get() = animator

    /** Receives header gestures and the COLL-4 event. */
    var events: StripEvents?
        get() = headers.listener
        set(value) {
            headers.listener = value
        }

    init {
        Disposer.register(parent, this)
        Disposer.register(this, disposable)
        tabs.add(hook)
        PillAwareTabsBorder.installOn(tabs, { geometry.pillCuts() }, { spareRow.rect })
        tabs.add(preview.gap)
        tabs.add(emptyOverlay)
        tabs.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                val file = newSelection?.`object` as? VirtualFile ?: return
                if (ownChanges > 0) {
                    // Our own re-ordering (show, sort, hide again around collapsed groups; collapse and expand) makes the platform move
                    // the selection, also onto a tab that is still hidden. That is no navigation by the user: reporting it as COLL-4
                    // would expand the group (animated) in the middle of a drop.
                    if (!newSelection.isHidden) collapse.visibleTabSelected()
                    tabs.repaint()
                    return
                }
                if (newSelection.isHidden) {
                    // COLL-4: the group's owner expands the group; show() then selects `file` for real.
                    collapse.hiddenTabSelected(file) { groupId -> events?.hiddenTabSelected(groupId, file) }
                }
                else {
                    collapse.visibleTabSelected()
                    // A tab that is shrinking away was selected (click, Ctrl+E): same as COLL-4, the owner expands the group, which
                    // reverses the animation. Our own selection change at the start of a collapse never lands here (it leaves the group).
                    animator.collapsingGroupOf(file)?.let { events?.hiddenTabSelected(it, file) }
                }
                tabs.repaint()
            }

            override fun tabRemoved(tabToRemove: TabInfo) {
                val file = tabToRemove.`object` as? VirtualFile ?: return
                transit.forget(file)
                collapse.tabRemoved(file, layout.tabBefore(file))
                tint.forget(tabToRemove)
                updateReservations()
                tabs.revalidate()
            }

            override fun tabsMoved() {
                updateReservations()
                tabs.revalidate()
            }
        }, disposable)
    }

    // ---- read-only helpers ----------------------------------------------------------------------------------------------

    /** Files of the tabs currently shown in the strip, in order. */
    fun visibleFiles(): List<VirtualFile> = StripReader.visibleFiles(tabs)

    /** The selected file, or null if nothing is selected / everything is hidden. */
    fun selectedFile(): VirtualFile? = StripReader.selectedFile(tabs)

    /** The split's whole tab order with the hidden tabs of collapsed groups at their headers' places (see [CollapseController.logicalOrder]). */
    fun logicalOrder(): List<VirtualFile> = collapse.logicalOrder(visibleFiles())

    /** The right-clicked tab if a tab context menu is showing, otherwise the selected tab. */
    fun contextFile(): VirtualFile? = StripReader.contextFile(window)

    /** The header components of this tab bar, for hit-testing and drag and drop. */
    fun headerComponents(): List<GroupHeader> = headers.all

    // ---- group state -----------------------------------------------------------------------------------------------------

    /**
     * Replaces the groups shown in this tab bar. Creates/removes headers and collapses/expands tabs to match. [animate]: a group whose
     * collapsed flag differs from what the strip shows collapses / expands with the COLL-7 animation (only [SplitBar] passes true, for
     * a host refresh); false snaps every running animation to its end first. A strip that cannot animate (hidden tab bar,
     * not on screen) is instant. A group that is new or gone never animates.
     *
     * A collapse moves the selection off the group now (COLL-2), the tabs are hidden when the animation ends; an expand shows the tabs
     * at once and the animator grows them from nothing. [before]: the groups a flip is measured against (the host's previous state,
     * passed by [SplitBar]); null = what this strip was last given, which during a drag can be a temporary all-expanded state.
     */
    fun setGroups(newGroups: List<GroupDecoration>, animate: Boolean = false, before: List<GroupDecoration>? = null) = own {
        if (animate && canAnimate()) startAnimations(newGroups, before ?: groups) else animator.finishAll()
        groups = newGroups
        applyShown()
        // The tabs of an expanding group were un-hidden by applyShown and must have their first (empty) width before the next paint.
        animator.applySizes()
        tabs.revalidate()
    }

    /**
     * The group [groupId] of [newGroups] comes back expanded after a drag that showed it collapsed (INT-6, COLL-7): it plays the expand
     * animation (unless [StripStyle.animateCollapse], SET-10, is off or the strip cannot animate), and nothing else does: every other
     * group is measured against itself, so its flag is no flip. For a drag that ends without a commit; a commit goes through
     * `SplitBar.apply`, which passes the same measure.
     */
    fun expandDropped(newGroups: List<GroupDecoration>, groupId: String) =
        setGroups(newGroups, style().animateCollapse, expandMeasure(newGroups, groupId))

    /**
     * Selects [file] (a drag that moved the selection off a group's tabs puts it back). The strip's own change: no COLL-4. No-op for
     * null, for a file that is not open or hidden here, and if it is selected already.
     */
    fun reselect(file: VirtualFile?) = own {
        if (file == null || !alive() || StripReader.selectedFile(tabs) == file) return@own
        val info = StripReader.infoOf(tabs, file) ?: return@own
        if (!info.isHidden) window.setSelectedComposite(file, false)
    }

    /** Depth of the adapter's own strip changes; selection events during them are not the user's (see the `selectionChanged` listener). */
    private var ownChanges = 0

    /**
     * Runs [block] as a change of the strip's own (no selection made during it is the user's navigation, see the `selectionChanged`
     * listener). For multi-step operations driven from outside, such as moving a group to another split (`GroupMove`).
     */
    fun <T> ownChange(block: () -> T): T {
        ownChanges++
        try {
            return block()
        }
        finally {
            ownChanges--
        }
    }

    private inline fun own(block: () -> Unit) {
        ownChanges++
        try {
            block()
        }
        finally {
            ownChanges--
        }
    }

    private fun canAnimate(): Boolean = !tabs.isHideTabs && isShowing()

    private fun startAnimations(newGroups: List<GroupDecoration>, before: List<GroupDecoration>) {
        val collapsingFiles = newGroups.filter { it.collapsed }.flatMapTo(HashSet()) { it.members }
        val old = before.associateBy { it.id }
        animator.retain(newGroups.mapTo(HashSet()) { it.id })
        for (g in newGroups) {
            val before = old[g.id] ?: continue
            val running = animator.direction(g.id)
            if (g.collapsed == (running ?: before.collapsed)) {
                if (running != null) animator.updateMembers(g.id, g.members)
                continue
            }
            if (g.members.none { StripReader.infoOf(tabs, it) != null }) continue
            // COLL-2 / COLL-7: the selection changes at the start, the editor never waits for the animation.
            if (g.collapsed) collapse.selectOutside(collapsingFiles)
            animator.begin(g.id, g.members, g.collapsed)
        }
    }

    /** Brings headers, hidden tabs, tint and pill space in line with [shown]. */
    private fun applyShown() {
        val now = shown
        headers.sync(now)
        collapse.sync(now)
        val tints = HashMap<VirtualFile, Color>()
        for (g in now) if (!g.collapsed) {
            val color = tint.colorFor(g.color)
            for (f in g.members) tints[f] = color
        }
        tint.setDesired(tints)
        updateReservations()
        tabs.revalidate()
        tabs.repaint()
    }

    /**
     * Snaps every running collapse / expand animation to its end (a drag must start from a settled strip): the tabs of a group that
     * was collapsing are hidden now.
     */
    fun finishCollapseAnimations() {
        if (!alive() || !animator.isActive) return
        own {
            animator.finishAll()
            applyShown()
        }
    }

    /**
     * The tint colour tabs of group [groupId] have in this strip (a `GroupTintColor`, evaluated at paint time), or null if the group is
     * not in this split or is collapsed. The floating picture of a tab dragged in from another split is tinted with it (DRAG-7).
     */
    fun tintFor(groupId: String): Color? = shown.firstOrNull { it.id == groupId && !it.collapsed }?.let { tint.colorFor(it.color) }

    /** The strip's current style (pill font size, name length), e.g. for the group drag picture. */
    fun currentStyle(): StripStyle = style()

    /**
     * The style (see [StripStyle]) changed: headers re-measure (their reserved width depends on the maximum name length) and repaint,
     * the tint is repainted (its colours read the style at paint time) and the strip is laid out again.
     */
    fun restyle() {
        if (!alive()) return
        headers.restyle()
        tint.restyle()
        updateReservations()
        tabs.revalidate()
        tabs.repaint()
    }

    /**
     * Re-orders the *visible* tabs to [visibleOrder] and leaves the tabs hidden by collapsed groups where they are (behind the visible
     * ones, each collapsed group keeping its anchor). Files of [visibleOrder] that are not visible are ignored; visible tabs it does
     * not list keep their relative order behind the listed ones. Goes through [applyOrder] (the only correct way around hidden tabs).
     *
     * [CollapseController.applyOrder] shows every hidden tab, sorts by the given order (a file missing from it sorts last) and hides
     * them again, re-deriving their order from the strip. Passing only visible files would leave the hidden tabs' relative order to
     * the order in which the platform re-inserts them, so the full order is built here: visible tabs as asked, hidden ones behind
     * them in their current order (the invariant the collapse code keeps: hidden tabs at the end of the full tab list).
     */
    fun reorderVisible(visibleOrder: List<VirtualFile>) {
        if (!alive()) return
        val visible = visibleFiles()
        val wanted = visibleOrder.toSet()
        val listed = visibleOrder.filter { it in visible }.distinct()
        // Our own left-to-right record, not `JBTabsImpl.getTabs()`: that re-inserts hidden tabs by iterating a HashMap, so their order
        // there is arbitrary, and feeding it back would swap the tabs of a collapsed group when it is expanded.
        val hiddenNow = collapse.hiddenFiles.toList()
        applyOrder(listed + visible.filter { it !in wanted } + hiddenNow)
    }

    /**
     * Re-pins collapsed tabs to the end of the full tab list if new tabs were opened since they were hidden (see
     * [CollapseController.repin]) and refreshes tint and pill placement.
     */
    fun repinHidden() = own {
        tint.refresh()
        updateReservations()
        if (collapse.repin()) {
            updateReservations()
            tabs.revalidate()
        }
    }

    /**
     * Re-orders all tabs of the split, collapsed ones included, to [order]. Hidden tabs keep their stale remembered index inside
     * `JBTabsImpl`, so this shows everything, sorts and hides again. Use this for any re-order around collapsed groups (drag and drop).
     */
    fun applyOrder(order: List<VirtualFile>) = own {
        collapse.applyOrder(order)
        updateReservations()
        tabs.revalidate()
    }

    /**
     * The header component of [groupId] if it has a visible area in the strip, else null. Lays the strip out first if it is invalid:
     * [PillHeaders.place] runs from [adjust], i.e. in the layout pass after a change, so right after [setGroups] the header still
     * has its old (or empty) bounds.
     */
    fun showingHeader(groupId: String): GroupHeader? {
        if (!alive()) return null
        if (!tabs.component.isValid) tabs.component.validate()
        return headers.of(groupId)?.takeIf { !it.bounds.isEmpty }
    }

    // ---- drag preview (the plugin's own tab drag) ----------------------------------------------------------------------

    /**
     * Drag preview: while the plugin's own tab drag is running, treat [file] as being in group [groupId] (null = no group) for
     * pill placement and tint. The pill then sits before the first *preview* member, so a member dragged out in front of its
     * pill does not carry the pill along (if no preview member is left in view, the real members decide). Membership itself is
     * unchanged until the drag commits through [setGroups]. Replaces an earlier preview of the same file. The tint is shown at
     * once (group colour of [groupId], or none); the space of the pill moves with the next layout pass (requested here).
     * Call [clearDragPreview] when the drag ends or is cancelled.
     */
    fun setDragPreview(file: VirtualFile, groupId: String?) {
        if (!alive()) return
        preview.setMembership(file, groupId)
        val group = groupId?.let { id -> shown.firstOrNull { it.id == id } }
        if (group != null) tint.setPreview(file, group.color) else tint.setPreviewUntinted(file)
        updateReservations()
        tabs.revalidate()
        tabs.repaint()
    }

    /**
     * The plugin's own tab drag of [file] begins: remembers which tabs stand in front of every expanded group, so that a group whose
     * only tab is dragged away keeps its pill where it was (the pill is then hosted like the pill of a collapsed group, by the tab
     * that followed it, and never by the dragged tab). Call before the first re-order of the drag; [clearDragPreview] ends it.
     */
    fun beginTabDrag(file: VirtualFile) {
        if (!alive()) return
        preview.begin(file, shown, visibleFiles())
    }

    /**
     * Shows the platform's empty drop slot (see [DragGap]) over the tabs of [files] and the pills of [pillGroups]: the place of what
     * is being dragged. It follows the layout (the strip is re-ordered live under it). Empty arguments remove it.
     */
    fun setDragGap(files: Collection<VirtualFile>, pillGroups: Collection<String>) {
        if (!alive()) return
        preview.setGap(files, pillGroups)
        headers.suppress(pillGroups.toSet())
        updateGap()
        tabs.repaint()
    }

    /**
     * Opens space in this strip for a group that is dragged in from another split: [length] along the strip (Top / Bottom: a border
     * inset of the label at [anchor], like a pill's space; Left / Right: rows, the labels behind move down), shown as the same drop
     * slot as an own drag. Null closes it. The neighbours move on the next layout pass (requested here). [pinned]: what arrives is a pinned
     * tab; an unpinned arrival at the end of a strip with only pinned tabs and a separate pinned row gets its gap in the row after the
     * pinned one ([SpareRow]).
     */
    fun setForeignGap(anchor: ForeignAnchor?, length: Int, pinned: Boolean = false) {
        if (!alive()) return
        val wanted = anchor?.let { ForeignGap(it, length, pinned) }
        val old = layout.foreign
        if ((old == null) == (wanted == null) && old?.anchor == wanted?.anchor && old?.length == wanted?.length && old?.pinned == wanted?.pinned) return
        layout.foreign = wanted
        updateReservations()
        tabs.revalidate()
        tabs.repaint()
    }

    /** The visible part of the space opened by [setForeignGap] (tab-bar coordinates), or null. */
    fun foreignGapRect(): Rectangle? = if (layout.foreign == null) null else geometry.spacerRect()

    /** Ends every drag preview of this split (pill placement and tint go back to the real membership) and removes the drop slot. */
    fun clearDragPreview() {
        headers.suppress(emptySet())
        setForeignGap(null, 0, false)
        val had = preview.clear()
        if (!had) return
        tint.clearPreviews()
        updateReservations()
        updateGap()
        tabs.revalidate()
        tabs.repaint()
    }

    // ---- the dragged tab leaves the strip (DRAG-5) --------------------------------------------------------------------------------

    /**
     * The tab of [file] leaves this strip while its drag is over another split's strip, as it does in the platform's own drag out (see
     * [TabTransit]): the editor area selects the tab it would select if this one was closed, then the tab is hidden. The strip must be
     * in the order the drag began with (the caller puts it back first) and have no drag preview. The pill of a group whose only tab it
     * is stays where it was (DRAG-8). False (nothing changed) if the tab cannot leave. Close it with [endTransit].
     */
    fun beginTransit(file: VirtualFile): Boolean {
        if (!alive()) return false
        var left = false
        own { left = transit.begin(file, shown) }
        if (left) {
            updateReservations()
            tabs.revalidate()
            tabs.repaint()
        }
        return left
    }

    /**
     * The tab in transit comes back: [show] it again in its place (not if it is about to be closed by a move), and with [reselect] select
     * the tab that was selected when it left (Esc, back on this strip); a copy shows it and leaves the selection as it is, like the
     * platform's drop with Ctrl. No-op if no tab is in transit.
     */
    fun endTransit(show: Boolean, reselect: Boolean) {
        if (!alive()) return
        own { transit.end(show, reselect) }
        updateReservations()
        tabs.revalidate()
        tabs.repaint()
    }

    /** The file whose tab is in transit (out of this strip because its drag is over another split), or null. */
    fun transitFile(): VirtualFile? = transit.state?.file

    /**
     * A dragged group is out of this strip ([groupId]) or back in it (null), as the tab in [beginTransit]: while its drag is over another
     * split's strip (or no strip) its pill is not laid out, so there is no pill, no reserved space and no placeholder and the neighbours
     * close up; the group's tabs stay hidden and in the collapse bookkeeping (it is collapsed for the drag), so the anchors of collapsed
     * neighbours that pass through it still resolve. The host model is not touched. Idempotent.
     */
    fun setGroupTransit(groupId: String?) {
        if (!alive() || groupInTransit == groupId) return
        groupInTransit = groupId
        own { updateReservations() }
        tabs.revalidate()
        tabs.repaint()
    }

    fun groupInTransit(): String? = groupInTransit

    // ---- strip geometry for the drag layer ---------------------------------------------------------------------------------

    /** The visible units of the strip in the order the user sees them (see [StripGeometry.units]). */
    fun stripUnits(): List<StripUnit> {
        if (!alive()) return emptyList()
        if (!tabs.component.isValid) tabs.component.validate()
        return geometry.units()
    }

    /** The file whose label hosts the pill of [groupId], and whether on its leading side (tests only). Null if the pill is not hosted. */
    /** Where the pill of [groupId] is now, from the labels' current bounds (a test seam for the geometry), or null if it has no place. */
    internal fun pillArea(groupId: String): dev.willcodes.bettereditortabs.tabgroups.model.strip.PillHosting.PillRect? =
        headers.of(groupId)?.let { geometry.pillAreas()[it] }

    internal fun pillHost(groupId: String): Pair<VirtualFile?, Boolean>? = layout.pillHost(groupId)

    /** The rectangles the drop slot covers now, tab-bar coordinates (tests only). */
    internal fun dragGapRuns(): List<Rectangle> = preview.gap.currentRuns().map { Rectangle(it) }

    /** The row beside a separate pinned row that holds loose pills ([SpareRow]), or null (tests only). */
    internal fun spareRowRect(): java.awt.Rectangle? = spareRow.rect

    /** The space reserved in the label of [file] (leading, trailing), tests only. */
    internal fun reservedSpace(file: VirtualFile): Pair<Int, Int>? =
        StripReader.infoOf(tabs, file)?.let { tabs.getTabLabel(it) }?.let { layout.space.reserved(it) }

    // ---- platform tab drag -------------------------------------------------------------------------------------------------

    /**
     * True while the platform's own `DragHelper` is dragging a tab of this bar inside the strip. The platform positions the dragged
     * label itself then, and this adapter leaves its bounds alone.
     */
    fun isPlatformTabDragActive(): Boolean = PlatformInternals.isTabDragActive(tabs)

    /**
     * True while this tab bar is the drop target of a platform drag (`JBTabsImpl.startDropOver` made a drop label, `isDropTarget`; both
     * public API): a tab dragged in by the platform's own drag-out, or a file from the Project view. The platform then decides where the
     * drop goes from `getComponentAt(point)` (`SingleRowLayout.getDropIndexFor`), which must find a tab label and not a pill.
     */
    fun isPlatformDropTarget(): Boolean = tabs.component.components.any { it is TabLabel && tabs.isDropTarget(it.info) }

    /**
     * The pills are transparent to the pointer (hit testing, hover) while the platform's own drag works on this strip, as a drag in
     * it ([isPlatformTabDragActive]) or as a drop into it ([isPlatformDropTarget]). Over a pill the platform then finds the label that
     * hosts the pill's space, so a tab dropped there lands next to the pill's host label instead of the drop turning into a new split
     * (`getComponentAt` returned the pill, no label, index -1, `getDropSideFor`). A pill hosted in front of its label: the tab lands
     * in front of that label, i.e. in front of the pill. A pill hosted behind a label (a collapsed group): in front of that label.
     */
    private fun platformDecidesHits(): Boolean = isPlatformTabDragActive() || isPlatformDropTarget()

    /** The file of the tab being dragged by the platform (the selected tab, as in `draggedTabSelectionInfo`), or null. */
    internal fun platformDraggedFile(): VirtualFile? =
        if (isPlatformTabDragActive()) StripReader.selectedFile(tabs) else null

    private fun platformDraggedLabel(): TabLabel? =
        if (isPlatformTabDragActive()) tabs.selectedInfo?.let { tabs.getTabLabel(it) } else null

    /**
     * Nothing tells us that a platform tab drag has started (its first event only moves the dragged label, no layout pass).
     * The pills are repainted by the drag (the dragged label is repainted over the strip), so they check the drag state while
     * painting and ask for a layout when it changed; [updateReservations] then moves the pill's space off the dragged label.
     */
    private fun pollDragState() {
        val active = isPlatformTabDragActive()
        if (active == dragWasActive) return
        dragWasActive = active
        SwingUtilities.invokeLater {
            if (!alive()) return@invokeLater
            if (active) finishCollapseAnimations()
            tabs.revalidate()
        }
    }

    // ---- close selection safety -----------------------------------------------------------------------------------------------

    /** Called before a file is closed (see [CloseSelectionGuard]). */
    fun beforeFileClosed(file: VirtualFile) = closeGuard.beforeFileClosed(file)

    // ---- layout hook -----------------------------------------------------------------------------------------------------

    /**
     * Zero-size child that is always "invalid", so `Container.validateTree` of the tab component calls its [doLayout]
     * right after the platform finished its own `doLayout`. That is the only synchronous "after platform layout" callback
     * available without subclassing the (private) editor tab component.
     */
    private inner class LayoutHook : JPanel(null) {
        init {
            isOpaque = false
            isFocusable = false
        }

        override fun isValid(): Boolean = false
        override fun contains(x: Int, y: Int): Boolean = false
        override fun doLayout() {
            adjust()
        }
    }

    private fun updateReservations(): Boolean = layout.update()

    /** Runs after every platform layout pass (from `validateTree`). It never moves a tab label. */
    private fun adjust() {
        if (!alive()) return
        if (updateReservations() && !layingOutAgain) {
            // A reservation changed after the platform had laid out (platform drag started or moved the first member, a border
            // was replaced, ...). Lay out once more so the pass the user sees already contains the pill's space.
            layingOutAgain = true
            try {
                tabs.doLayout()
                for (info in tabs.getVisibleInfos()) tabs.getTabLabel(info)?.let { if (!it.isValid) it.validate() }
            }
            finally {
                layingOutAgain = false
            }
        }
        dragWasActive = isPlatformTabDragActive()
        verticalRows.apply()
        spareRow.apply()
        headers.place(geometry.pillAreas(), preview.gap)
        updateGap()
        val showEmpty = !tabs.isHideTabs && tabs.width > 0 && tabs.isEmptyVisible && collapse.hasHiddenFiles()
        emptyOverlay.update(showEmpty, geometry.emptyStrip(tabs.tabsPosition))
    }

    /** Places the drop slot over the current rectangles of what it covers; the pills are raised above it again. */
    private fun updateGap() {
        if (!alive()) return
        val foreign = if (layout.foreign == null) emptyList() else listOfNotNull(geometry.spacerRect())
        if (preview.layoutGap(tabs.isHorizontalTabs, { geometry.units() }, foreign)) headers.raise(preview.gap)
    }

    override fun dispose() {
        disposed = true
        animator.dispose()
        transit.end(show = !window.isDisposed, reselect = false)
        collapse.releaseAll(showTabs = !window.isDisposed)
        layout.dispose()
        PillAwareTabsBorder.uninstallFrom(tabs)
        tabs.remove(hook)
        tabs.remove(emptyOverlay)
        tabs.remove(preview.gap)
        headers.dispose()
        tabs.revalidate()
    }
}

/**
 * The groups a flip is measured against when group [groupId] comes back expanded after being dragged as its collapsed header (INT-6,
 * COLL-7): [groups] with that group collapsed, so it flips and nothing else does.
 */
internal fun expandMeasure(groups: List<GroupDecoration>, groupId: String): List<GroupDecoration> =
    groups.map { if (it.id == groupId) it.copy(collapsed = true) else it }
