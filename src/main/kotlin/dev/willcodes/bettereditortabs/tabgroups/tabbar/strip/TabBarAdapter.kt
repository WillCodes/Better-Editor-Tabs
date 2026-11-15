package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EmptyPillSpots
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripItem
import dev.willcodes.bettereditortabs.tabgroups.model.strip.TabOrderRules
import dev.willcodes.bettereditortabs.tabgroups.model.strip.files
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.StripStyle
import java.awt.Color
import javax.swing.JPanel

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
 *   dragged group in the same situation is simply not laid out ([StripDragSurface.setGroupTransit]);
 * - [DragPreview]: previewed membership, dragged file and the drop slot ([DragGap]) during the plugin's own drag;
 * - [StripDragSurface] (`drag`): the drag surface callers use: drag preview, drop slot, foreign gap, transit and the platform's drag state;
 * - [TabTint]: the group colour on member tabs (`TabInfo.setTabColor`);
 * - [EmptyEditorOverlay]: the empty state when every tab is hidden;
 * - [StripBackdrop]: the strip row's background and lines while the strip shows only pills (bug 11);
 * - [RuleOutline]: POP-4, the outline around the tabs an auto-group rule matches (the group popup's preview);
 * - [StripRightClick]: CTX-5, a right click on the free part of the strip;
 * - [SpareRow]: the row for pills beside a separate pinned row when no unpinned tab is left to host them;
 * - [CloseSelectionGuard]: keeps a close from selecting a hidden tab;
 * - [PillAwareTabsBorder]: cuts the pills' slots out of the strip border;
 * - [StripSort]: IDE-2, the alphabetical order of a strip that has groups (pinned, groups by name, ungrouped; the platform's own sorting
 *   is off for such a strip and the manual order is remembered and put back).
 *
 * Hosting: all extra components (headers, a layout hook, the drop slot, the empty-state overlay) are direct children of the tab
 * component. A header's *space* is part of a tab label: the host label gets a border inset of the pill's length, so the platform's
 * own layouts (single row, scrollable, multi row, side placements, overflow) already count it. After each layout pass
 * [LayoutHook.doLayout], called by `validateTree`, only *places* the pill component over the reserved area. Labels are never moved
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

    /** The real groups, as last set. */
    private var groups: List<GroupDecoration> = emptyList()

    /**
     * What the strip shows: [groups], except that a group whose collapse is still animating counts as expanded (its tabs are visible,
     * tinted, the pill is hosted by its first tab). Every collaborator reads this one.
     *
     * RULE-6: an *empty* instance (no member has a tab here; a group with an enabled rule keeps its instance when its last tab goes)
     * is shown as a collapsed group's pill with nothing hidden, whatever its flag says (HDR-5: the header looks the same). It is one
     * strip unit like a collapsed pill (DRAG-6); [CollapseController] keeps its place.
     */
    private val shown: List<GroupDecoration>
        get() {
            val real = if (!animator.isActive) groups
            else groups.map { if (it.collapsed && animator.isCollapsing(it.id)) it.copy(collapsed = false) else it }
            if (real.isEmpty()) return real
            val open = openFiles()
            if (real.all { g -> g.collapsed || g.members.any { it in open } }) return real
            return real.map { g -> if (!g.collapsed && g.members.none { it in open }) g.copy(collapsed = true) else g }
        }

    /** The files that have a tab in this strip, hidden ones included. */
    private fun openFiles(): Set<VirtualFile> = StripReader.openFiles(tabs)

    /** The groups whose pills the strip lays out: [shown] without the group in transit (its tabs stay hidden by [collapse]). */
    private val pillGroups: List<GroupDecoration>
        get() = drag.groupInTransit()?.let { id -> shown.filter { it.id != id } } ?: shown

    /**
     * [pillGroups] for the hosting of the pills: an empty instance that a dragged tab would join (DRAG-3 hold behind its pill, previewed by
     * [DragPreview]) counts as an expanded group with that tab as its member, so its pill is hosted by the dragged tab. The strip units
     * ([StripGeometry]) keep seeing the empty pill, so the drop rules judge the same cells before and after the hold completes.
     */
    private val layoutGroups: List<GroupDecoration>
        get() {
            val base = pillGroups
            if (!preview.hasMembershipPreview) return base
            return base.map { g -> if (g.collapsed && g.id in collapse.emptyGroups() && preview.joins(g.id)) g.copy(collapsed = false) else g }
        }

    private val collapse = CollapseController(tabs, window)
    private val preview = DragPreview(tabs)
    private val tint = TabTint(tabs, this, { !window.isDisposed }, style)
    private val headers: PillHeaders = PillHeaders(tabs, { drag.platformDecidesHits() }, { drag.pollDragState() }, { drag.platformDraggedLabel() }, style)
    private val transit = TabTransit(tabs, window, collapse)
    private val layout: PillLayout = PillLayout(tabs, alive, { layoutGroups }, headers::of, preview, collapse, { drag.platformDraggedFile() }) { transit.state }
    private val animator = CollapseAnimator(tabs, layout.space) { if (alive()) own { applyShown() } }
    private val geometry: StripGeometry = StripGeometry(tabs, alive, layout, { pillGroups }, preview, { spareRow.rect }, { verticalRows.rowHeight(it) }, { id -> id in collapse.emptyGroups() })

    /** The drag surface: drag preview, transit and the platform's drag state (see [StripDragSurface]). */
    val drag: StripDragSurface = StripDragSurface(
        tabs, alive, preview, transit, headers, layout, geometry, tint, { shown }, { block -> ownChange(block) }, ::finishCollapseAnimations,
        { sort.recheckLater() },
    )
    private val emptyOverlay = EmptyEditorOverlay(tabs, window)
    private val backdrop = StripBackdrop(tabs)
    private val outline = RuleOutline(tabs)
    private val freeArea = FreeStripArea(tabs, geometry, { layout.hasLoosePills() }, { headers.all }) { stripUnits() }
    private val rightClick = StripRightClick(tabs, { p -> isFreeStripPoint(p) }, { dragActive() || drag.isForeignBusy() }) { component, point ->
        events?.stripRightClicked(component, point)
    }
    private val closeGuard = CloseSelectionGuard(tabs, window, collapse)
    private val verticalRows: VerticalRows = VerticalRows(tabs, layout, geometry, animator::verticalExtentOf)
    private val spareRow = SpareRow(tabs, layout)
    private val hook = LayoutHook()

    /** A drag of ours is running here (tab or group, set by the drag layer); the sort never re-orders then. */
    internal var dragActive: () -> Boolean = { false }

    private val sort = StripSort(
        tabs, alive, { groups }, { logicalOrder() }, { applyOrder(it) }, { applyGroups(it, false, null) },
        { dragActive() || drag.isForeignBusy() }, { events?.stripSorted(it) }, { collapse.emptyAnchors() }, { placeEmptyPills(it) },
    )

    /** True while the plugin sorts this strip (IDE-2: "Sort tabs alphabetically" is on and the strip has groups). */
    val ownsSort: Boolean get() = sort.isOwning

    /**
     * `UISettings` changed: sorting may have been switched on or off, and the tab placement may have changed. A placement change is
     * not animated (COLL-7): running collapse / expand animations snap to their end. Read from `UISettings` itself, so it does not
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

    /** IDE-2: the manual order while the plugin sorts this strip, else null (see [StripSort.manualOrder]). */
    fun manualOrder(): List<VirtualFile>? = sort.manualOrder()

    /** IDE-2: the manual order stored before a restart (see [StripSort.seedManual]). */
    fun seedManualOrder(order: List<VirtualFile>) = sort.seedManual(order)

    /** Sorts now if the plugin sorts this strip and it is out of order (a new tab was placed: the sort replaces MEM-2). */
    fun sortNow() = sort.sync()

    private var layingOutAgain = false

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
        // POP-4 (fix round 2): the outline is painted from the border wrapper, after the platform's own border layer
        outline.paintsAfterBorder = PillAwareTabsBorder.installOn(tabs, { geometry.pillCuts() }, { spareRow.rect }, { g -> outline.paintAfterChildren(g) }) != null
        tabs.add(preview.gap)
        tabs.add(emptyOverlay)
        tabs.add(backdrop)
        tabs.add(outline)
        tabs.setComponentZOrder(outline, 0)
        rightClick.install()
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
    fun setGroups(newGroups: List<GroupDecoration>, animate: Boolean = false, before: List<GroupDecoration>? = null) {
        sort.beforeGroups(newGroups)
        applyGroups(newGroups, animate, before)
        sort.afterGroups()
    }

    /** [setGroups] without the sort hooks (the sort itself re-orders around collapsed groups with it). */
    private fun applyGroups(newGroups: List<GroupDecoration>, animate: Boolean, before: List<GroupDecoration>?) = own {
        captureSpots()
        seatFirstMembers(newGroups)
        keepEmptyOutsideGroups(newGroups)
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
        val open = openFiles()
        animator.retain(newGroups.mapTo(HashSet()) { it.id })
        for (g in newGroups) {
            val before = old[g.id] ?: continue
            val running = animator.direction(g.id)
            // RULE-6: a group that had no tab here (an empty instance) and gets its first one is not a collapse or an expand: a
            // collapsed group shows the arrival hidden at once, an expanded one shows it, neither animates (COLL-7 is for flips of
            // groups with open tabs)
            if (running == null && before.members.none { it in open }) continue
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
     * A group drag shows its group collapsed (INT-6) while [on]: if that hides every tab, the editor keeps showing (as during the
     * platform's drag-out) instead of going empty (COLL-2). Off when the drag ends.
     */
    fun keepEditorDuringDrag(on: Boolean) {
        collapse.keepEditor = on
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
     * not in this split or is collapsed (an empty instance, RULE-6, counts as a group a tab can join: DRAG-3). The floating picture of a tab dragged in from another split is tinted with it (DRAG-7).
     */
    fun tintFor(groupId: String): Color? =
        shown.firstOrNull { it.id == groupId && (!it.collapsed || groupId in collapse.emptyGroups()) }?.let { tint.colorFor(it.color) }

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

    // ---- empty instances (RULE-6) ------------------------------------------------------------------------------------------

    /**
     * Remembers where every expanded group's tabs stand (the tab in front of its first tab), so that the pill of a group that loses
     * its last tab stays there ([CollapseController.captureSpots]). Not while a drag runs: the drag moves tabs live, and the result
     * of a drop reaches the strip as new groups after the drag state is gone.
     */
    private fun captureSpots() {
        if (!isQuiet()) return
        collapse.captureSpots(shown, visibleFiles())
    }

    /** No drag moves tabs around here (ours, the platform's, a foreign gap, a previewed membership or a tab in transit). */
    private fun isQuiet(): Boolean = alive() && !dragActive() && !drag.isForeignBusy() && drag.platformDraggedFile() == null &&
        preview.draggedFile == null && !preview.hasMembershipPreview && transit.state == null

    /**
     * Fix round 2, bug 1: an empty pill never stands inside another group's span. A tab that a rule or the join path adds to a group at
     * its end lands behind the empty pill that stood behind the group's old last tab (`[G a b][E] c`); with [newGroups] applied the pill
     * would be in the middle of G. It is put behind G's new last tab instead ([EmptyPillSpots.outsideSpans]), before the groups are
     * applied. Not during a drag (the live order is a preview) and not while the plugin sorts the strip (the sort places the pills).
     */
    private fun keepEmptyOutsideGroups(newGroups: List<GroupDecoration>) {
        if (!isQuiet() || sort.isOwning || collapse.emptyGroups().isEmpty()) return
        val groupOf = HashMap<VirtualFile, String>()
        for (g in newGroups) for (f in g.members) groupOf.putIfAbsent(f, g.id)
        val sequence = collapse.sequence(visibleFiles())
        val fixed = EmptyPillSpots.outsideSpans(sequence) { groupOf[it] }
        if (fixed === sequence) return
        collapse.placeEmpty(EmptyPillSpots.derive(fixed))
        updateReservations()
    }

    /**
     * RULE-6, bug 12: an empty group that gets its first member(s) keeps its pill where it was and the new members stand right behind
     * the pill, in front of whatever followed it (other empty pills included). The strip is put in that order before the groups are
     * applied: the members come from wherever the platform or the host's `openInOrder` put them, the pill's place is the sequence's
     * ([CollapseController.sequence]), and the empty pills that stood behind it are anchored behind the arrivals. Nothing animates.
     * A collapsed group keeps its pill and its anchor as it is: its members are hidden at the pill's place anyway. Not while the plugin
     * sorts the strip (the sort puts the group among the groups by name).
     */
    private fun seatFirstMembers(newGroups: List<GroupDecoration>) {
        if (!alive() || sort.isOwning) return
        val empty = collapse.emptyGroups()
        if (empty.isEmpty()) return
        val visible = visibleFiles()
        val arrivals = LinkedHashMap<String, List<VirtualFile>>()
        for (g in newGroups) {
            if (g.id !in empty || g.collapsed) continue
            val members = g.members.toHashSet()
            val here = visible.filter { it in members } // the strip's own order
            if (here.isNotEmpty()) arrivals[g.id] = here
        }
        if (arrivals.isEmpty()) return
        val seated = arrivals.values.flatMapTo(HashSet()) { it }
        val items = ArrayList<StripItem<VirtualFile>>()
        for (item in collapse.sequence(visible)) when {
            item is StripItem.Tab && item.key in seated -> Unit
            item is StripItem.EmptyPill && item.groupId in arrivals -> arrivals.getValue(item.groupId).mapTo(items) { StripItem.Tab(it) }
            else -> items += item
        }
        collapse.applyOrder(items.files())
        collapse.placeEmpty(EmptyPillSpots.derive(items))
        updateReservations()
    }

    /** The ids of the groups the strip shows as their pill alone (RULE-6), as of the last group change. */
    fun emptyGroupIds(): Set<String> = collapse.emptyGroups()

    /**
     * RULE-6: moves the pills of empty groups behind the tabs [spots] gives (see [CollapseController.placeEmpty]); what a drag or the
     * sort does after it has put the tabs into the order it wants. Ids that are not empty groups are ignored. Lays the strip out again.
     */
    fun placeEmptyPills(spots: Map<String, VirtualFile?>) = own {
        if (!alive() || spots.isEmpty()) return@own
        collapse.placeEmpty(spots)
        updateReservations()
        tabs.revalidate()
        tabs.repaint()
    }

    /**
     * PERS-3 amended: puts the pill of the empty group [groupId] behind the first [index] tabs of [logicalOrder] (0 = in front of all,
     * past the end = at the end), never inside another group's span (the pill goes behind that group, [keepEmptyOutsideGroups]). Not an
     * empty group of this strip: no-op.
     */
    fun placeEmptyAt(groupId: String, index: Int) = own {
        if (!alive() || groupId !in collapse.emptyGroups()) return@own
        val items = collapse.sequence(visibleFiles()).filterNot { it is StripItem.EmptyPill && it.groupId == groupId }.toMutableList()
        var at = if (index <= 0) 0 else items.size
        if (index > 0) {
            var tabsSeen = 0
            for ((i, item) in items.withIndex()) if (item is StripItem.Tab && ++tabsSeen == index) {
                at = i + 1
                break
            }
        }
        // behind the pills already standing there: several empty groups stored at one place come back in their stored order
        while (at < items.size && items[at] is StripItem.EmptyPill) at++
        items.add(at, StripItem.EmptyPill(groupId))
        val members = HashMap<VirtualFile, String>()
        for (g in groups) for (f in g.members) members.putIfAbsent(f, g.id)
        collapse.placeEmpty(EmptyPillSpots.derive(EmptyPillSpots.outsideSpans(items) { members[it] }))
        updateReservations()
        tabs.revalidate()
        tabs.repaint()
    }

    /** The anchor (the tab it stands behind) of every empty group's pill, null = in front of all tabs (see [CollapseController.emptyAnchors]). */
    fun emptyAnchors(): Map<String, VirtualFile?> = collapse.emptyAnchors()

    /**
     * Where [groupId]'s header stands: the number of tabs of [logicalOrder] in front of it (the index of the first tab of a group with
     * tabs, the place of the pill of an empty group), null if this strip has no header for the group (not one of the groups last set).
     */
    fun headerIndex(groupId: String): Int? {
        val g = groups.firstOrNull { it.id == groupId } ?: return null
        val sequence = collapse.sequence(visibleFiles())
        val pill = sequence.indexOfFirst { it is StripItem.EmptyPill && it.groupId == groupId }
        if (pill >= 0) return sequence.subList(0, pill).files().size
        val members = g.members.toHashSet()
        return sequence.files().indexOfFirst { it in members }.takeIf { it >= 0 }
    }

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

    /** The row beside a separate pinned row that holds loose pills ([SpareRow]), or null (tests only). */
    internal fun spareRowRect(): java.awt.Rectangle? = spareRow.rect

    /** The space reserved in the label of [file] (leading, trailing), tests only. */
    internal fun reservedSpace(file: VirtualFile): Pair<Int, Int>? =
        StripReader.infoOf(tabs, file)?.let { tabs.getTabLabel(it) }?.let { layout.space.reserved(it) }

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
        lastLaidOutOrder = logicalOrder()
        captureSpots()
        drag.syncDragState()
        verticalRows.apply()
        spareRow.apply()
        headers.place(geometry.pillAreas(), preview.gap)
        drag.updateGap()
        // a drag that takes the last tab or group out of the strip keeps the editor showing, as the platform's drag-out does
        val dragging = collapse.keepEditor || transit.state != null
        // Also with placement None (IDE-5): the strip is gone but the split still has only hidden tabs, and without the overlay the
        // platform paints a blank editor area there (no strip to paint then).
        // SPLIT-6: the placeholder tab of a temporary split is hidden like a collapsed tab, so the split shows the empty state too
        val showEmpty = tabs.width > 0 && tabs.isEmptyVisible && (collapse.hasHiddenFiles() || StripReader.holdsPlaceholder(tabs)) && !dragging
        // bug 11: a strip that shows only pills (no tab for the platform to draw a strip for) gets its row's background and lines. The
        // backdrop goes to the bottom of the z-order first and the overlay below it after, so that the row's lines show over the overlay too.
        val pillsOnly = tabs.width > 0 && !tabs.isHideTabs && tabs.isEmptyVisible && layout.hasLoosePills()
        backdrop.update(pillsOnly, if (pillsOnly) geometry.emptyStrip(tabs.tabsPosition) else java.awt.Rectangle())
        emptyOverlay.update(showEmpty, if (tabs.isHideTabs) java.awt.Rectangle() else geometry.emptyStrip(tabs.tabsPosition))
        updateOutline()
    }

    /**
     * POP-4: outlines the tabs of [files] in [color] (the group's colour) in this strip, replacing the previous outline; an empty
     * collection or a null colour clears it. Purely visual ([RuleOutline]); tabs hidden by collapsed groups are not outlined.
     */
    fun setRuleOutline(files: Collection<VirtualFile>, color: Color?) {
        if (!alive()) return
        val was = outline.isActive
        outline.set(files, color)
        if (was || outline.isActive) updateOutline()
    }

    /** The files whose tabs are outlined now (tests). */
    internal fun outlinedFiles(): Set<VirtualFile> = outline.outlined()

    /** The rectangles of the outlines drawn now (tests). */
    internal fun outlineRects(): List<java.awt.Rectangle> = outline.shown()

    private fun updateOutline() = outline.refresh(!tabs.isHideTabs, preview.draggedFile) {
        geometry.units().filterIsInstance<StripUnit.Tab>().map { it.file to it.rect }
    }

    /** The row of the strip the backdrop paints while the strip shows only pills (bug 11), or null (tests: it is what the pills-only row looks like). */
    internal fun pillsOnlyRow(): java.awt.Rectangle? = backdrop.row.takeIf { !it.isEmpty }

    /** CTX-5: true if [p] (tab component coordinates) is on the free part of the tab strip (see [FreeStripArea]). */
    internal fun isFreeStripPoint(p: java.awt.Point): Boolean = alive() && freeArea.contains(p)

    /** The logical order as of the last layout pass: the order before a pin change, which the platform applies lazily (see [pinChanged]). */
    private var lastLaidOutOrder: List<VirtualFile> = emptyList()

    /**
     * A tab of this strip was pinned or unpinned (`FileEditorManagerListener.filePinStateChanged`, which the platform publishes after
     * setting the flag and before its next layout). With "keep pinned tabs on the left" the platform groups the pinned tabs first
     * lazily, in `JBTabsImpl.getVisibleInfos`, by *swapping* a pinned tab with the first unpinned one, so that tab jumps to where the
     * pinned one was, e.g. behind a group, and the group looks moved. Here the strip is re-ordered first: pinned tabs first, everything
     * else in the order of the last layout pass ([TabOrderRules.pinnedFirst]); the platform's grouping then finds nothing to swap.
     * Not while the plugin sorts the strip (the sort puts pinned tabs first itself) or a drag runs.
     */
    fun pinChanged() {
        if (!alive() || sort.isOwning || dragActive() || drag.isForeignBusy()) return
        // the platform's alphabetical view (a strip without groups) orders pinned tabs itself
        if (UISettings.getInstance().sortTabsAlphabetically || !keepPinnedTabsFirst()) return
        // read the raw list without `getVisibleInfos`, which would apply the platform's swap right now
        val pinned = tabs.tabs.filter { it.isPinned }.mapNotNullTo(HashSet()) { it.`object` as? VirtualFile }
        val all = StripReader.allFiles(tabs)
        val wanted = TabOrderRules.pinnedFirst(lastLaidOutOrder, all) { it in pinned }
        if (wanted != lastLaidOutOrder) applyOrder(wanted)
    }

    /** The platform setting "keep pinned tabs on the left" (`AdvancedSettings`, public API); on if it cannot be read. */
    private fun keepPinnedTabsFirst(): Boolean =
        try { AdvancedSettings.getBoolean("editor.keep.pinned.tabs.on.left") } catch (e: Exception) { true }

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
