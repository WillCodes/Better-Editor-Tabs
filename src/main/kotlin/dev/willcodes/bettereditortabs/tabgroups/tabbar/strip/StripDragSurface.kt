package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.ui.tabs.impl.TabLabel
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.Rectangle
import javax.swing.SwingUtilities

/**
 * The drag surface of one strip, owned by its [TabBarAdapter] (`adapter.drag`): what the plugin's own drag (tab or group) does to the
 * strip, and what the platform's drag state means for it.
 *
 * - the drag preview: previewed membership, the drop slot ([DragGap]) and the space for a tab or group dragged in from another split
 *   ([DragPreview], [ForeignGap]);
 * - the transit: the dragged tab that is out of this strip while its drag is over another split ([TabTransit]), and a dragged
 *   group in the same situation ([setGroupTransit]);
 * - the platform's own tab drag: whether it runs in or into this strip, which the pills must ignore ([isPlatformTabDragActive]).
 *
 * The adapter's group state reads [groupInTransit] (the pills it lays out); everything else it does to the strip is its own business.
 * EDT only.
 */
internal class StripDragSurface(
    private val tabs: JBEditorTabs,
    private val alive: () -> Boolean,
    private val preview: DragPreview,
    private val transit: TabTransit,
    private val headers: PillHeaders,
    private val layout: PillLayout,
    private val geometry: StripGeometry,
    private val tint: TabTint,
    /** What the strip shows (the adapter's `shown`: collapsing groups count as expanded). */
    private val shown: () -> List<GroupDecoration>,
    /** Runs a block as a change of the strip's own (the adapter's `ownChange`). */
    private val own: (() -> Unit) -> Unit,
    /** Snaps every running collapse / expand animation to its end (the adapter's `finishCollapseAnimations`). */
    private val finishCollapseAnimations: () -> Unit,
    /** A drag state of this strip ended (preview, foreign gap, transit): the sort looks again ([TabBarAdapter]'s `recheckSort`). */
    private val settled: () -> Unit,
) {
    private var groupInTransit: String? = null
    private var dragWasActive = false

    // ---- drag preview (the plugin's own tab drag) ----------------------------------------------------------------------

    /**
     * Drag preview: while the plugin's own tab drag is running, treat [file] as being in group [groupId] (null = no group) for
     * pill placement and tint. The pill then sits before the first *preview* member, so a member dragged out in front of its
     * pill does not carry the pill along (if no preview member is left in view, the real members decide). Membership itself is
     * unchanged until the drag commits through `setGroups`. Replaces an earlier preview of the same file. The tint is shown at
     * once (group colour of [groupId], or none); the space of the pill moves with the next layout pass (requested here).
     * Call [clearDragPreview] when the drag ends or is cancelled.
     */
    fun setDragPreview(file: VirtualFile, groupId: String?) = setDragPreview(file, groupId, emptyMap())

    /** [setDragPreview] with the drag's decision for the parked groups (group id -> the dragged tab stands in front of its pill). */
    fun setDragPreview(file: VirtualFile, groupId: String?, parkedSides: Map<String, Boolean>) {
        if (!alive()) return
        preview.setMembership(file, groupId)
        preview.setParkedSides(parkedSides)
        val group = groupId?.let { id -> shown().firstOrNull { it.id == id } }
        if (group != null) tint.setPreview(file, group.color) else tint.setPreviewUntinted(file)
        relayout()
    }

    /**
     * The plugin's own tab drag of [file] begins: remembers which tabs stand in front of every expanded group, so that a group whose
     * only tab is dragged away keeps its pill where it was (the pill is then hosted like the pill of a collapsed group, by the tab
     * that followed it, and never by the dragged tab). Call before the first re-order of the drag; [clearDragPreview] ends it.
     */
    fun beginTabDrag(file: VirtualFile) {
        if (!alive()) return
        preview.begin(file, shown(), StripReader.visibleFiles(tabs))
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
        relayout()
        if (wanted == null) settled()
    }

    /** The visible part of the space opened by [setForeignGap] (tab-bar coordinates), or null. */
    fun foreignGapRect(): Rectangle? = if (layout.foreign == null) null else geometry.spacerRect()

    /** Ends every drag preview of this split (pill placement and tint go back to the real membership) and removes the drop slot. */
    fun clearDragPreview() {
        headers.suppress(emptySet())
        setForeignGap(null, 0, false)
        val had = preview.clear()
        settled()
        if (!had) return
        tint.clearPreviews()
        layout.update()
        updateGap()
        tabs.revalidate()
        tabs.repaint()
    }

    /** The rectangles the drop slot covers now, tab-bar coordinates (tests only). */
    internal fun dragGapRuns(): List<Rectangle> = preview.gap.currentRuns().map { Rectangle(it) }

    /** Places the drop slot over the current rectangles of what it covers; the pills are raised above it again. */
    fun updateGap() {
        if (!alive()) return
        val foreign = if (layout.foreign == null) emptyList() else listOfNotNull(geometry.spacerRect())
        if (preview.layoutGap(tabs.isHorizontalTabs, { geometry.units() }, foreign)) headers.raise(preview.gap)
    }

    // ---- the dragged tab leaves the strip --------------------------------------------------------------------------------

    /**
     * The tab of [file] leaves this strip while its drag is over another split's strip, as it does in the platform's own drag out (see
     * [TabTransit]): the editor area selects the tab it would select if this one was closed, then the tab is hidden. The strip must be
     * in the order the drag began with (the caller puts it back first) and have no drag preview. The pill of a group whose only tab it
     * is stays where it was. False (nothing changed) if the tab cannot leave. Close it with [endTransit].
     */
    fun beginTransit(file: VirtualFile): Boolean {
        if (!alive()) return false
        var left = false
        own { left = transit.begin(file, shown()) }
        if (left) relayout()
        return left
    }

    /**
     * The tab in transit comes back: shows it again in its place ([show]; not if it is about to be closed by a move), and with [reselect]
     * selects the tab that was selected when it left (Esc, back on this strip); a copy shows it and leaves the selection as it is, like
     * the platform's drop with Ctrl. No-op if no tab is in transit.
     */
    fun endTransit(show: Boolean, reselect: Boolean) {
        if (!alive()) return
        own { transit.end(show, reselect) }
        relayout()
        settled()
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
        own { layout.update() }
        tabs.revalidate()
        tabs.repaint()
        if (groupId == null) settled()
    }

    /**
     * A drag started elsewhere is working on this strip: it has a gap open for an arriving tab or group, or a dragged tab or group of
     * this strip is out of it (transit). The alphabetical sort leaves the strip alone then.
     */
    fun isForeignBusy(): Boolean = layout.foreign != null || transit.state != null || groupInTransit != null

    /** The group whose drag is over another split (or no strip), see [setGroupTransit]; the adapter leaves its pill out. */
    fun groupInTransit(): String? = groupInTransit

    // ---- platform tab drag -------------------------------------------------------------------------------------------------

    /**
     * True while the platform's own `DragHelper` is dragging a tab of this bar inside the strip. The platform positions the dragged
     * label itself then, and the adapter leaves its bounds alone.
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
    fun platformDecidesHits(): Boolean = isPlatformTabDragActive() || isPlatformDropTarget()

    /** The file of the tab being dragged by the platform (the selected tab, as in `draggedTabSelectionInfo`), or null. */
    fun platformDraggedFile(): VirtualFile? =
        if (isPlatformTabDragActive()) StripReader.selectedFile(tabs) else null

    fun platformDraggedLabel(): TabLabel? =
        if (isPlatformTabDragActive()) tabs.selectedInfo?.let { tabs.getTabLabel(it) } else null

    /**
     * Nothing tells us that a platform tab drag has started (its first event only moves the dragged label, no layout pass).
     * The pills are repainted by the drag (the dragged label is repainted over the strip), so they check the drag state while
     * painting and ask for a layout when it changed; the adapter's reservations then move the pill's space off the dragged label.
     */
    fun pollDragState() {
        val active = isPlatformTabDragActive()
        if (active == dragWasActive) return
        dragWasActive = active
        SwingUtilities.invokeLater {
            if (!alive()) return@invokeLater
            if (active) finishCollapseAnimations()
            tabs.revalidate()
        }
    }

    /** Records the platform drag state a layout pass saw (see [pollDragState]). */
    fun syncDragState() {
        dragWasActive = isPlatformTabDragActive()
    }

    private fun relayout() {
        layout.update()
        tabs.revalidate()
        tabs.repaint()
    }
}
