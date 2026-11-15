package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.impl.JBEditorTabs
import dev.willcodes.bettereditortabs.tabgroups.model.strip.GroupLines
import java.awt.Color
import java.awt.Rectangle
import javax.swing.JPanel

/**
 * The strip's post-layout hook (HDR placement, bug 11 backdrop, empty state, POP-4 outline, LINE-6 line): a zero-size child that is always "invalid",
 * so `Container.validateTree` of the tab component calls its [doLayout] right after the platform finished its own `doLayout`. That is
 * the only synchronous "after platform layout" callback available without subclassing the (private) editor tab component.
 *
 * After each pass it makes sure the pills' space is reserved ([PillLayout.update], laying out once more if that changed), records the
 * logical order, places the headers, and updates the components it drives. It never moves a tab label.
 */
internal class StripLayoutHook(
    private val tabs: JBEditorTabs,
    private val alive: () -> Boolean,
    private val layout: PillLayout,
    private val collapse: CollapseController,
    private val transit: TabTransit,
    private val preview: DragPreview,
    private val drag: () -> StripDragSurface,
    private val headers: PillHeaders,
    private val counts: CountPills,
    private val geometry: StripGeometry,
    private val verticalRows: VerticalRows,
    private val spareRow: SpareRow,
    private val backdrop: StripBackdrop,
    private val emptyOverlay: EmptyEditorOverlay,
    private val outline: RuleOutline,
    /** LINE-6: the Islands group line and, per group to underline, its colour and visible units (see [GroupLine.refresh]). */
    private val groupLine: GroupLine,
    private val lineGroups: () -> List<Pair<Color, List<GroupLines.Unit>>>,
    private val logicalOrder: () -> List<VirtualFile>,
    /** Receives the logical order of each layout pass (see [StripOrder.lastLaidOutOrder]). */
    private val laidOut: (List<VirtualFile>) -> Unit,
    private val captureSpots: () -> Unit,
) : JPanel(null) {
    private var layingOutAgain = false

    init {
        isOpaque = false
        isFocusable = false
    }

    override fun isValid(): Boolean = false
    override fun contains(x: Int, y: Int): Boolean = false
    override fun doLayout() {
        adjust()
    }

    /** Runs after every platform layout pass (from `validateTree`). It never moves a tab label. */
    private fun adjust() {
        if (!alive()) return
        if (layout.update() && !layingOutAgain) {
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
        laidOut(logicalOrder())
        captureSpots()
        drag().syncDragState()
        verticalRows.apply()
        spareRow.apply()
        headers.place(geometry.pillAreas(), preview.gap)
        counts.place(geometry.countAreas()) // COLL-8: after the headers, so the count stays above the drop slot
        drag().updateGap()
        // a drag that takes the last tab or group out of the strip keeps the editor showing, as the platform's drag-out does
        val dragging = collapse.keepEditor || transit.state != null
        // Also with placement None (IDE-5): the strip is gone but the split still has only hidden tabs, and without the overlay the
        // platform paints a blank editor area there (no strip to paint then).
        // SPLIT-6: the placeholder tab of a temporary split is hidden like a collapsed tab, so the split shows the empty state too
        val showEmpty = tabs.width > 0 && tabs.isEmptyVisible && (collapse.hasHiddenFiles() || StripReader.holdsPlaceholder(tabs)) && !dragging
        // bug 11: a strip that shows only pills (no tab for the platform to draw a strip for) gets its row's background and lines. The
        // backdrop goes to the bottom of the z-order first and the overlay below it after, so that the row's lines show over the overlay too.
        val pillsOnly = tabs.width > 0 && !tabs.isHideTabs && tabs.isEmptyVisible && layout.hasLoosePills()
        backdrop.update(pillsOnly, if (pillsOnly) geometry.emptyStrip(tabs.tabsPosition) else Rectangle())
        emptyOverlay.update(showEmpty, if (tabs.isHideTabs) Rectangle() else geometry.emptyStrip(tabs.tabsPosition))
        updateOutline()
        groupLine.refresh(!tabs.isHideTabs, lineGroups) // LINE-6: after the headers and counts are placed
    }

    /** Brings the POP-4 outline in line with the current tab bounds (also called when the outline is set). */
    fun updateOutline() = outline.refresh(!tabs.isHideTabs, preview.draggedFile) {
        geometry.units().filterIsInstance<StripUnit.Tab>().map { it.file to it.rect }
    }
}
