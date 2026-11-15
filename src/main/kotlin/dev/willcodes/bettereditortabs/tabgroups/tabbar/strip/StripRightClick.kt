package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ui.tabs.impl.JBEditorTabs
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripDragGeometry
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * A right click on the free part of a split's tab strip, where there is no tab label and no pill, is
 * reported through [onFreeClick] with the tab component and the point (tab component coordinates), so the host can open a context menu
 * (New Group).
 *
 * What the platform does there today (262 sources): nothing. The tab context menu is the label's own (`TabLabel.handlePopup`, from its
 * `mousePressed` / `mouseClicked` / `mouseReleased`, which needs `clickCount == 1` and `isPopupTrigger`); `JBTabsImpl` has no popup of its
 * own, and `EditorTabbedContainer`'s listener on the tab component (`mouseClicked`) only maximises the split on a left-button double click
 * outside every label. This is a mouse listener on the same component, so it only receives what no child takes: a label, one of our
 * pills ([HeaderMouseHandler] consumes its events) and the platform's buttons get their events first. [isFree] is the exact test anyway (the
 * strip's rows, minus labels, pills and the platform's more / entry-point buttons). Tab menus, the pill's own right click and the double
 * click on the free area are therefore untouched; this listener consumes only the event it reports.
 *
 * A popup trigger is reported once per gesture: on press where the platform's popup trigger is the press (macOS, Linux), on release where
 * it is the release (Windows), never both. Not while a drag of ours runs ([blocked]).
 *
 * EDT only.
 */
internal class StripRightClick(
    private val tabs: JBEditorTabs,
    private val isFree: (Point) -> Boolean,
    private val blocked: () -> Boolean,
    private val onFreeClick: (JComponent, Point) -> Unit,
) : MouseAdapter() {
    private var reported = false

    fun install() {
        tabs.component.addMouseListener(this)
    }

    fun uninstall() {
        tabs.component.removeMouseListener(this)
    }

    override fun mousePressed(e: MouseEvent) {
        reported = false
        if (e.isPopupTrigger) report(e)
    }

    override fun mouseReleased(e: MouseEvent) {
        if (e.isPopupTrigger && !reported) report(e)
        reported = false
    }

    private fun report(e: MouseEvent) {
        if (e.isConsumed || e.clickCount > 1 || blocked()) return
        val p = e.point
        if (!isFree(p)) return
        reported = true
        e.consume()
        onFreeClick(tabs.component, Point(p))
    }
}

/**
 * The free part of a tab strip, where [StripRightClick] reports a right click: the strip's rows (its units, the platform's header
 * rectangle, or the row of a strip that shows only pills, [hasLoosePills]), minus every tab label, every pill and count pill ([headers]) and the
 * platform's buttons (more, entry point, title). A strip that shows nothing (placement None, no tab and no pill) has no free part.
 */
internal class FreeStripArea(
    private val tabs: JBEditorTabs,
    private val geometry: StripGeometry,
    private val hasLoosePills: () -> Boolean,
    private val headers: () -> List<JComponent>,
    private val units: () -> List<StripUnit>,
) {
    /** True if [p] (tab component coordinates) is on the free part of the strip. */
    fun contains(p: Point): Boolean {
        if (tabs.isHideTabs || tabs.width <= 0 || tabs.height <= 0) return false
        val horizontal = tabs.isHorizontalTabs
        val rects = ArrayList<Rectangle>()
        if (tabs.isEmptyVisible) {
            if (!hasLoosePills()) return false
            rects += geometry.emptyStrip(tabs.tabsPosition)
        }
        else {
            tabs.lastLayoutPass?.headerRectangle?.let { rects += Rectangle(it) }
        }
        for (u in units()) rects += u.rect
        val band = StripDragGeometry.crossBand(rects, horizontal) ?: return false
        if (horizontal && (p.y !in band || p.x < 0 || p.x >= tabs.width)) return false
        if (!horizontal && (p.x !in band || p.y < 0 || p.y >= tabs.height)) return false
        for (info in tabs.getVisibleInfos()) {
            val b = tabs.getTabLabel(info)?.bounds ?: continue
            if (!b.isEmpty && b.contains(p)) return false
        }
        if (headers().any { !it.bounds.isEmpty && it.bounds.contains(p) }) return false
        val pass = tabs.lastLayoutPass
        if (pass != null && listOf(pass.moreRect, pass.entryPointRect, pass.titleRect).any { !it.isEmpty && it.contains(p) }) return false
        return true
    }
}
