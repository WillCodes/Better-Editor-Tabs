package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.StripStyle
import dev.willcodes.bettereditortabs.tabgroups.model.strip.GroupLines
import java.awt.Color

/**
 * Per-split coordinator around one editor tab bar (`EditorWindow.tabbedPane.tabs`, a `JBEditorTabs`). It owns the tab bar's extra
 * children and wires the collaborators together; each collaborator owns one concern and wraps its own internal API:
 *
 * - [CollapseController]: hiding and showing the tabs of collapsed groups, anchors, saved selection (`TabInfo.isHidden`, `sortTabs`);
 * - [CollapseAnimator]: the collapse / expand animation: while a group collapses its tabs are still visible and the
 *   collaborators see the group as expanded ([GroupsApplier.shown]); the animator sets the member labels' preferred widths each frame and
 *   the platform lays the strip out (`TabLabel.preferredSize`). The tabs are hidden when it ends;
 * - [GroupsApplier]: which groups the strip has and shows ([GroupsApplier.shown]), applying new groups (headers, hidden tabs, tint, the
 *   animations' start) and the own-change counter that tells the strip's own changes from the user's navigation;
 * - [EmptyPills]: the bookkeeping of empty-group pills (where they stand, seating a first member, placing them);
 * - [StripOrder]: re-ordering around collapsed groups, re-pinning hidden tabs, the pin-change order fix;
 * - [StripLayoutHook]: the post-layout pass (pill space, header placement, backdrop, empty state, outline);
 * - [PillLayout]: which tab label hosts each pill and the space reserved there ([TabLabelSpace], `TabLabel.border`);
 * - [StripGeometry]: pill areas, border cuts and [StripUnit]s from the labels' bounds;
 * - [PillHeaders]: the header components, their placement and z-order;
 * - [CountPills]: the "+N" count behind the peek of a partly shown group: a collapsed group that shows one member beside its header
 *   (the peek, [GroupDecoration.peek]) and the number of members still hidden. The count's space is reserved in the trailing side of the
 *   peek's label like a pill's (Top / Bottom) or a row of its own below the peek (Left / Right); a click on it is a click on the header;
 * - [TabTransit]: the dragged tab that is out of this strip while its drag is over another split (hidden, selection handed over); a
 *   dragged group in the same situation is simply not laid out ([StripDragSurface.setGroupTransit]);
 * - [DragPreview]: previewed membership, dragged file and the drop slot ([DragGap]) during the plugin's own drag;
 * - [StripDragSurface] (`drag`): the drag surface callers use: drag preview, drop slot, foreign gap, transit and the platform's drag state;
 * - [TabTint]: the group colour on member tabs (`TabInfo.setTabColor`);
 * - [EmptyEditorOverlay]: the empty state when every tab is hidden;
 * - [StripBackdrop]: the strip row's background and lines while the strip shows only pills;
 * - [RuleOutline]: the outline around the tabs an auto-group rule matches (the group popup's preview);
 * - [StripRightClick]: a right click on the free part of the strip;
 * - [SpareRow]: the row for pills beside a separate pinned row when no unpinned tab is left to host them;
 * - [CloseSelectionGuard]: keeps a close from selecting a hidden tab;
 * - [PillAwareTabsBorder]: cuts the pills' slots out of the strip border;
 * - [StripSort]: the alphabetical order of a strip that has groups (pinned, groups by name, ungrouped; the platform's own sorting
 *   is off for such a strip and the manual order is remembered and put back).
 *
 * Hosting: all extra components (headers, a layout hook, the drop slot, the empty-state overlay) are direct children of the tab
 * component. A header's *space* is part of a tab label: the host label gets a border inset of the pill's length, so the platform's
 * own layouts (single row, scrollable, multi row, side placements, overflow) already count it. After each layout pass
 * [StripLayoutHook.doLayout], called by `validateTree`, only *places* the pill component over the reserved area. Labels are never moved
 * by us ([dev.willcodes.bettereditortabs.tabgroups.model.strip.PillHosting]).
 *
 * Internal APIs the coordinator itself wraps: `TabsListener` (selection / removal / move events), `JBTabsImpl.doLayout` and
 * `validateTree` (the layout hook), the platform drag state ([StripDragSurface.isPlatformTabDragActive]).
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

    private val collapse: CollapseController = CollapseController(tabs, window)
    private val preview: DragPreview = DragPreview(tabs)
    private val tint: TabTint = TabTint(tabs, this, { !window.isDisposed }, style)
    private val headers: PillHeaders = PillHeaders(tabs, { drag.platformDecidesHits() }, { drag.pollDragState() }, { drag.platformDraggedLabel() }, style)
    private val transit: TabTransit = TabTransit(tabs, window, collapse)
    private val counts: CountPills = CountPills(tabs, headers::of, { drag.platformDecidesHits() }, { drag.platformDraggedLabel() }, style)
    private val layout: PillLayout = PillLayout(
        tabs, alive, { applier.layoutGroups }, headers::of, preview, collapse, { drag.platformDraggedFile() }, { counts.lengthOf(it) },
    ) { transit.state }
    private val animator: CollapseAnimator = CollapseAnimator(tabs, layout.space) { if (alive()) applier.own { applier.applyShown() } }

    /** Which groups the strip has and shows, and the own-change counter (see [GroupsApplier]). */
    private val applier: GroupsApplier = GroupsApplier(
        tabs, alive, collapse, animator, headers, counts, tint, preview, { drag }, { updateReservations() },
        { !tabs.isHideTabs && isShowing() }, { emptyPills.beforeGroups(it) },
    )
    private val geometry: StripGeometry = StripGeometry(tabs, alive, layout, { applier.pillGroups }, preview, { spareRow.rect }, { verticalRows.rowHeight(it) }, { id -> id in collapse.emptyGroups() })

    /** The drag surface: drag preview, transit and the platform's drag state (see [StripDragSurface]). */
    val drag: StripDragSurface = StripDragSurface(
        tabs, alive, preview, transit, headers, layout, geometry, tint, { applier.shown }, { block -> applier.ownChange(block) },
        { applier.finishCollapseAnimations() },
        { sort.recheckLater() },
    )
    private val emptyOverlay = EmptyEditorOverlay(tabs, window)
    private val backdrop = StripBackdrop(tabs)
    private val outline = RuleOutline(tabs)
    private val groupLine = GroupLine(tabs)
    private val freeArea: FreeStripArea = FreeStripArea(tabs, geometry, { layout.hasLoosePills() }, { headers.all + counts.all }) { stripUnits() }
    private val rightClick = StripRightClick(tabs, { p -> isFreeStripPoint(p) }, { dragActive() || drag.isForeignBusy() }) { component, point ->
        events?.stripRightClicked(component, point)
    }
    private val closeGuard: CloseSelectionGuard = CloseSelectionGuard(tabs, window, collapse)
    private val verticalRows: VerticalRows = VerticalRows(tabs, layout, geometry, animator::verticalExtentOf)
    private val spareRow: SpareRow = SpareRow(tabs, layout)

    /** The empty-group pills' bookkeeping (see [EmptyPills]). */
    private val emptyPills: EmptyPills = EmptyPills(
        tabs, alive, collapse, applier, preview, transit, { drag }, { dragActive() }, { sort.isOwning }, { visibleFiles() },
        { updateReservations() },
    )

    /** Ordering around collapsed groups and the pin-change fix (see [StripOrder]). */
    private val order: StripOrder = StripOrder(
        tabs, alive, collapse, tint, applier, { drag }, { dragActive() }, { sort.isOwning }, { visibleFiles() }, { updateReservations() },
    )
    private val hook: StripLayoutHook = StripLayoutHook(
        tabs, alive, layout, collapse, transit, preview, { drag }, headers, counts, geometry, verticalRows, spareRow, backdrop, emptyOverlay,
        outline, groupLine, { lineGroups() }, { logicalOrder() }, { order.lastLaidOutOrder = it }, { emptyPills.captureSpots() },
    )

    /** A drag of ours is running here (tab or group, set by the drag layer); the sort never re-orders then. */
    internal var dragActive: () -> Boolean = { false }

    private val sort: StripSort = StripSort(
        tabs, alive, { applier.groups }, { logicalOrder() }, { applyOrder(it) }, { applier.applyGroups(it, false, null) },
        { dragActive() || drag.isForeignBusy() }, { events?.stripSorted(it) }, { collapse.emptyAnchors() }, { placeEmptyPills(it) },
    )

    /** True while the plugin sorts this strip ("Sort tabs alphabetically" is on and the strip has groups). */
    val ownsSort: Boolean get() = sort.isOwning

    /**
     * `UISettings` changed: sorting may have been switched on or off, and the tab placement may have changed. A placement change is
     * not animated: running collapse / expand animations snap to their end. Read from `UISettings` itself, so it does not
     * matter whether the platform's own listener has re-laid out the strip yet (listener order is undefined).
     */
    fun uiSettingsChanged() {
        val placement = UISettings.getInstance().editorTabPlacement
        if (placement != lastPlacement) {
            lastPlacement = placement
            finishCollapseAnimations()
        }
        sort.uiSettingsChanged()
    }

    /** The tab placement (`UISettings.editorTabPlacement`, `TABS_NONE` included) as of the last [uiSettingsChanged]. */
    private var lastPlacement: Int = UISettings.getInstance().editorTabPlacement

    /** A pin or a drag ended: the sorted order may be out of date (looked at after the current event). */
    fun recheckSort() = sort.recheckLater()

    /** The manual order while the plugin sorts this strip, else null (see [StripSort.manualOrder]). */
    fun manualOrder(): List<VirtualFile>? = sort.manualOrder()

    /** The manual order stored before a restart (see [StripSort.seedManual]). */
    fun seedManualOrder(order: List<VirtualFile>) = sort.seedManual(order)

    /** Sorts now if the plugin sorts this strip and it is out of order (a new tab was placed: the sort replaces the manual placement). */
    fun sortNow() = sort.sync()

    /** Whether the strip is on screen (an animation of a strip nobody sees is skipped); a test hook. */
    internal var isShowing: () -> Boolean = { tabs.component.isShowing }

    /** The animator (tests only: drive frames with [CollapseAnimator.tick] and an injected clock). */
    internal val collapseAnimator: CollapseAnimator get() = animator

    /** Receives header gestures and the hidden-tab selection event. */
    var events: StripEvents?
        get() = headers.listener
        set(value) {
            headers.listener = value
            counts.listener = value
        }

    init {
        Disposer.register(parent, this)
        Disposer.register(this, disposable)
        tabs.add(hook)
        // The rule outline and the Islands group line are painted from the border wrapper, after the
        // platform's own border layer (without the wrapper there is no group line: it would be covered by the labels)
        outline.paintsAfterBorder = PillAwareTabsBorder.installOn(tabs, { geometry.pillCuts() }, { spareRow.rect }) { g ->
            outline.paintAfterChildren(g)
            groupLine.paint(g)
        } != null
        tabs.add(preview.gap)
        tabs.add(emptyOverlay)
        tabs.add(backdrop)
        tabs.add(outline)
        tabs.setComponentZOrder(outline, 0)
        rightClick.install()
        tabs.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                val file = newSelection?.`object` as? VirtualFile ?: return
                if (applier.isOwnChange) {
                    // Our own re-ordering (show, sort, hide again around collapsed groups; collapse and expand) makes the platform move
                    // the selection, also onto a tab that is still hidden. That is no navigation by the user: reporting it as a navigation
                    // would expand the group (animated) in the middle of a drop.
                    if (!newSelection.isHidden) collapse.visibleTabSelected()
                    tabs.repaint()
                    return
                }
                // A partly shown group whose shown tab is no longer selected collapses again (the host decides), also when the
                // selection comes from the press that starts a tab drag (a partial group never stays open under a drag of
                // another tab). Not during a drag of ours: its own selection changes are part of the drag.
                if (!dragActive()) events?.tabSelected(file)
                if (newSelection.isHidden) {
                    // The group's owner expands the group; show() then selects `file` for real.
                    collapse.hiddenTabSelected(file) { groupId -> events?.hiddenTabSelected(groupId, file) }
                }
                else {
                    collapse.visibleTabSelected()
                    // A tab that is shrinking away was selected (click, Ctrl+E): same as a navigation to a hidden tab: the owner expands the group, which
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

    /**
     * The header at [point] (tab component coordinates): the header itself, or the header of the group whose count pill is there (the
     * count is part of the header, a drag that starts on it moves the group). Null if neither.
     */
    fun headerAt(point: java.awt.Point): GroupHeader? =
        headers.all.firstOrNull { !it.bounds.isEmpty && it.bounds.contains(point) }
            ?: counts.all.firstOrNull { !it.bounds.isEmpty && it.bounds.contains(point) }?.let { headers.of(it.groupId) }

    /** The count pill of [groupId] if it shows (has a visible area), else null (tests, and the group line). */
    internal fun countPill(groupId: String): CountPill? = counts.of(groupId)?.takeIf { !it.bounds.isEmpty }

    /**
     * The visible rectangle of the count pill of [groupId] in tab component coordinates (Top / Bottom: right behind the
     * peek; Left / Right: in the row below it), or null if the group shows no count. Lays the strip out first if it is invalid.
     */
    fun countArea(groupId: String): java.awt.Rectangle? {
        if (!alive()) return null
        if (!tabs.component.isValid) tabs.component.validate()
        return counts.of(groupId)?.bounds?.takeIf { !it.isEmpty }?.let { java.awt.Rectangle(it) }
    }

    /** The member that [groupId] shows beside its header while it is partly shown, else null (as the strip shows it now). */
    fun peekOf(groupId: String): VirtualFile? = applier.shown.firstOrNull { it.id == groupId }?.peek

    // ---- group state -----------------------------------------------------------------------------------------------------

    /**
     * Replaces the groups shown in this tab bar. Creates/removes headers and collapses/expands tabs to match. [animate]: a group whose
     * collapsed flag differs from what the strip shows collapses / expands with the collapse animation (only [SplitBar] passes true, for
     * a host refresh); false snaps every running animation to its end first. A strip that cannot animate (hidden tab bar,
     * not on screen) is instant. A group that is new or gone never animates.
     *
     * A collapse moves the selection off the group now, the tabs are hidden when the animation ends; an expand shows the tabs
     * at once and the animator grows them from nothing. [before]: the groups a flip is measured against (the host's previous state,
     * passed by [SplitBar]); null = what this strip was last given, which during a drag can be a temporary all-expanded state.
     */
    fun setGroups(newGroups: List<GroupDecoration>, animate: Boolean = false, before: List<GroupDecoration>? = null) {
        sort.beforeGroups(newGroups)
        applier.applyGroups(newGroups, animate, before)
        sort.afterGroups()
    }

    /**
     * The group [groupId] of [newGroups] comes back expanded after a drag that showed it collapsed: it plays the expand
     * animation (unless [StripStyle.animateCollapse] is off or the strip cannot animate), and nothing else does: every other
     * group is measured against itself, so its flag is no flip. For a drag that ends without a commit; a commit goes through
     * `SplitBar.apply`, which passes the same measure.
     */
    fun expandDropped(newGroups: List<GroupDecoration>, groupId: String) =
        setGroups(newGroups, style().animateCollapse, expandMeasure(newGroups, groupId))

    /**
     * [newGroups] differ from what the strip shows by a group that a tab drag expands (a hold behind its collapsed or partly shown
     * unit completed: its tabs grow out; it stays expanded), with the collapse animation (unless [StripStyle.animateCollapse] is off or the strip cannot animate). Every other group is measured against
     * what this strip was last given, so none of them animates.
     */
    fun expandForDrag(newGroups: List<GroupDecoration>) = setGroups(newGroups, style().animateCollapse)

    /**
     * Selects [file] (a drag that moved the selection off a group's tabs puts it back). The strip's own change: not a navigation to a hidden tab. No-op for
     * null, for a file that is not open or hidden here, and if it is selected already.
     */
    fun reselect(file: VirtualFile?) = applier.own {
        if (file == null || !alive() || StripReader.selectedFile(tabs) == file) return@own
        val info = StripReader.infoOf(tabs, file) ?: return@own
        if (!info.isHidden) window.setSelectedComposite(file, false)
    }

    /**
     * Runs [block] as a change of the strip's own (no selection made during it is the user's navigation, see the `selectionChanged`
     * listener). For multi-step operations driven from outside, such as moving a group to another split (`GroupMove`).
     */
    fun <T> ownChange(block: () -> T): T = applier.ownChange(block)

    /**
     * A group drag shows its group collapsed while [on]: if that hides every tab, the editor keeps showing (as during the
     * platform's drag-out) instead of going empty. Off when the drag ends.
     */
    fun keepEditorDuringDrag(on: Boolean) {
        collapse.keepEditor = on
    }

    /**
     * Snaps every running collapse / expand animation to its end (a drag must start from a settled strip): the tabs of a group that
     * was collapsing are hidden now.
     */
    fun finishCollapseAnimations() = applier.finishCollapseAnimations()

    /**
     * The tint colour tabs of group [groupId] have in this strip (a `GroupTintColor`, evaluated at paint time), or null if the group is
     * not in this split. A collapsed group counts as one a tab can join: an empty instance and, by a hold on its header,
     * a collapsed or partly shown one. The floating picture of a tab dragged in from another split is tinted with it.
     */
    fun tintFor(groupId: String): Color? =
        applier.shown.firstOrNull { it.id == groupId }?.let { tint.colorFor(it.color) }

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

    /** See [StripOrder.reorderVisible]. */
    fun reorderVisible(visibleOrder: List<VirtualFile>) = order.reorderVisible(visibleOrder)

    /** See [StripOrder.repinHidden]. */
    fun repinHidden() = order.repinHidden()

    /** See [StripOrder.applyOrder]. */
    fun applyOrder(order: List<VirtualFile>) = this.order.applyOrder(order)

    /**
     * The header component of [groupId] if it has a visible area in the strip, else null. Lays the strip out first if it is invalid:
     * [PillHeaders.place] runs from the layout hook, i.e. in the layout pass after a change, so right after [setGroups] the header still
     * has its old (or empty) bounds.
     */
    fun showingHeader(groupId: String): GroupHeader? {
        if (!alive()) return null
        if (!tabs.component.isValid) tabs.component.validate()
        return headers.of(groupId)?.takeIf { !it.bounds.isEmpty }
    }

    // ---- empty instances: see EmptyPills -------------------------------------------------------------------------

    /** See [EmptyPills.emptyGroupIds]. */
    fun emptyGroupIds(): Set<String> = emptyPills.emptyGroupIds()

    /** See [EmptyPills.placeEmptyPills]. */
    fun placeEmptyPills(spots: Map<String, VirtualFile?>) = emptyPills.placeEmptyPills(spots)

    /** See [EmptyPills.placeEmptyAt]. */
    fun placeEmptyAt(groupId: String, index: Int) = emptyPills.placeEmptyAt(groupId, index)

    /** See [EmptyPills.emptyAnchors]. */
    fun emptyAnchors(): Map<String, VirtualFile?> = emptyPills.emptyAnchors()

    /** See [EmptyPills.headerIndex]. */
    fun headerIndex(groupId: String): Int? = emptyPills.headerIndex(groupId)

    // ---- strip geometry for the drag layer ---------------------------------------------------------------------------------

    /**
     * For every group with visible tabs (expanded, or partially expanded), its colour and its visible units in strip
     * order: the pill as painted, its tabs (with a drag preview applied; not the dragged tab, whose place is the drop slot), its count.
     */
    private fun lineGroups(): List<Pair<Color, List<GroupLines.Unit>>> {
        val wanted = applier.shown.filter { !it.collapsed || it.peek != null }
        if (wanted.isEmpty()) return emptyList()
        val units = geometry.units()
        val dragged = preview.draggedFile
        return wanted.map { g ->
            g.color to units.mapNotNull { u ->
                when (u) {
                    is StripUnit.Pill -> if (u.groupId != g.id || u.rect.isEmpty) null else {
                        val h = u.header
                        val pill = h.pillBounds().also { it.translate(h.x, h.y) }.intersection(u.rect)
                        GroupLines.Unit(if (pill.isEmpty) u.rect else pill, false)
                    }
                    is StripUnit.Tab -> if (u.groupId == g.id && u.file != dragged) GroupLines.Unit(u.rect, true) else null
                    is StripUnit.Count -> if (u.groupId == g.id) GroupLines.Unit(u.rect, false) else null
                }
            }
        }
    }

    /** The Islands group lines drawn now, tab component coordinates (tests). */
    internal fun groupLineRects(): List<java.awt.Rectangle> = groupLine.shown()

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

    /** The row beside a separate pinned row that holds loose pills ([SpareRow]), or null (tests only). */
    internal fun spareRowRect(): java.awt.Rectangle? = spareRow.rect

    /** The space reserved in the label of [file] for pills (leading, trailing); also read by the drag's opening slot (`TabDragSession`). */
    internal fun reservedSpace(file: VirtualFile): Pair<Int, Int>? =
        StripReader.infoOf(tabs, file)?.let { tabs.getTabLabel(it) }?.let { layout.space.reserved(it) }

    // ---- close selection safety -----------------------------------------------------------------------------------------------

    /** Called before a file is closed (see [CloseSelectionGuard]). */
    fun beforeFileClosed(file: VirtualFile) = closeGuard.beforeFileClosed(file)

    private fun updateReservations(): Boolean = layout.update()

    /**
     * Outlines the tabs of [files] in [color] (the group's colour) in this strip, replacing the previous outline; an empty
     * collection or a null colour clears it. Purely visual ([RuleOutline]); tabs hidden by collapsed groups are not outlined.
     */
    fun setRuleOutline(files: Collection<VirtualFile>, color: Color?) {
        if (!alive()) return
        val was = outline.isActive
        outline.set(files, color)
        if (was || outline.isActive) hook.updateOutline()
    }

    /** The files whose tabs are outlined now (tests). */
    internal fun outlinedFiles(): Set<VirtualFile> = outline.outlined()

    /** The rectangles of the outlines drawn now (tests). */
    internal fun outlineRects(): List<java.awt.Rectangle> = outline.shown()

    /** The row of the strip the backdrop paints while the strip shows only pills, or null (tests: it is what the pills-only row looks like). */
    internal fun pillsOnlyRow(): java.awt.Rectangle? = backdrop.row.takeIf { !it.isEmpty }

    /** True if [p] (tab component coordinates) is on the free part of the tab strip (see [FreeStripArea]). */
    internal fun isFreeStripPoint(p: java.awt.Point): Boolean = alive() && freeArea.contains(p)

    /** See [StripOrder.pinChanged]. */
    fun pinChanged() = order.pinChanged()

    override fun dispose() {
        sort.release(windowAlive = !window.isDisposed)
        disposed = true
        animator.dispose()
        transit.end(show = !window.isDisposed, reselect = false)
        collapse.releaseAll(showTabs = !window.isDisposed)
        layout.dispose()
        PillAwareTabsBorder.uninstallFrom(tabs)
        tabs.remove(hook)
        rightClick.uninstall()
        tabs.remove(emptyOverlay)
        tabs.remove(backdrop)
        tabs.remove(outline)
        tabs.remove(preview.gap)
        counts.dispose()
        headers.dispose()
        tabs.revalidate()
    }
}

/**
 * The groups a flip is measured against when group [groupId] comes back expanded after being dragged as its collapsed header: [groups] with that group collapsed, so it flips and nothing else does.
 */
internal fun expandMeasure(groups: List<GroupDecoration>, groupId: String): List<GroupDecoration> =
    groups.map { if (it.id == groupId) it.copy(collapsed = true) else it }
