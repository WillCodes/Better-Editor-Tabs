package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.JBTabsPosition
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PillHosting
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PillRows
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import java.awt.Rectangle
import java.util.IdentityHashMap

/**
 * Geometry of the strip with pills in it: the strip rectangle, the pill areas inside their host labels, the cuts for the strip
 * border, and the strip units ([StripUnit]) that the drag layer works with.
 *
 * Reads the pill assignments of [PillLayout] and the labels' current bounds; changes nothing. Internal APIs wrapped:
 * `JBTabsImpl.headerFitSize`, `lastLayoutPass` (`moreRect`, `entryPointRect`), `tabsPosition`, `borderThickness`, `getTabLabel`.
 * EDT only.
 */
internal class StripGeometry(
    private val tabs: JBEditorTabs,
    private val alive: () -> Boolean,
    private val layout: PillLayout,
    private val groups: () -> List<GroupDecoration>,
    private val preview: DragPreview,
    /** The row beside a separate pinned row that holds loose pills ([SpareRow]), or null. */
    private val spareRow: () -> Rectangle? = { null },
) {
    fun stripThickness(horizontal: Boolean): Int {
        val fit = tabs.headerFitSize
        val t = if (fit == null) 0 else if (horizontal) fit.height else fit.width
        return if (t > 0) t else JBUI.scale(28)
    }

    private fun stripRect(position: JBTabsPosition): Rectangle {
        val t = stripThickness(position == JBTabsPosition.top || position == JBTabsPosition.bottom)
        return when (position) {
            JBTabsPosition.top -> Rectangle(0, 0, tabs.width, t)
            JBTabsPosition.bottom -> Rectangle(0, tabs.height - t, tabs.width, t)
            JBTabsPosition.left -> Rectangle(0, 0, t, tabs.height)
            JBTabsPosition.right -> Rectangle(tabs.width - t, 0, t, tabs.height)
        }
    }

    /** The strip rectangle in the all-hidden state: as thick as the pills need (side placements stack them). */
    fun emptyStrip(position: JBTabsPosition): Rectangle {
        val r = stripRect(position)
        if (position == JBTabsPosition.left || position == JBTabsPosition.right) {
            val need = layout.assignments.filter { it.label == null }.maxOfOrNull { it.length } ?: return r
            if (need > r.width) {
                r.width = need
                if (position == JBTabsPosition.right) r.x = tabs.width - need
            }
        }
        return r
    }

    /**
     * End of the visible strip along the main axis for the row (or column) of a rectangle: the component size, or the start of
     * the more / entry-point button when that button sits in the same row. Pills must end before it.
     */
    fun limitFunction(horizontal: Boolean): (Rectangle) -> Int {
        val base = if (horizontal) tabs.width else tabs.height
        val pass = tabs.lastLayoutPass
        val buttons = if (pass == null) emptyList() else listOf(pass.moreRect, pass.entryPointRect).filter { !it.isEmpty }
        return { row ->
            var limit = base
            for (b in buttons) {
                val sameRow = if (horizontal) b.y < row.y + row.height && b.y + b.height > row.y
                              else b.x < row.x + row.width && b.x + b.width > row.x
                val start = if (horizontal) b.x else b.y
                if (sameRow && start > 0) limit = minOf(limit, start)
            }
            limit
        }
    }

    private class Areas(val pills: LinkedHashMap<GroupHeader, PillHosting.PillRect>, val spacer: Rectangle?)

    /**
     * Where every pill is, from the labels' current bounds: [PillHosting.PillRect.full] is the pill's whole slot (inside the
     * reserved area of its host label in Top / Bottom, in a row of its own above or below its host label in Left / Right, or in the
     * empty strip when every tab is collapsed), `visible` the part inside the strip.
     */
    fun pillAreas(): LinkedHashMap<GroupHeader, PillHosting.PillRect> = areas().pills

    /** The visible part of the space opened for a group dragged in from another split ([ForeignGap]), or null. */
    fun spacerRect(): Rectangle? = areas().spacer

    private fun areas(): Areas {
        val result = LinkedHashMap<GroupHeader, PillHosting.PillRect>()
        var spacer: Rectangle? = null
        if (tabs.isHideTabs || tabs.width <= 0) return Areas(result, null)
        val horizontal = tabs.isHorizontalTabs
        val position = tabs.tabsPosition
        val limitOf = limitFunction(horizontal)
        for (info in tabs.getVisibleInfos()) {
            val label = tabs.getTabLabel(info) ?: continue
            val (lead, trail) = layout.itemsOf(label)
            if (lead.isEmpty() && trail.isEmpty()) continue
            val bounds = label.bounds
            val limit = limitOf(bounds)
            val items = lead + trail
            val rects: List<PillHosting.PillRect>
            if (horizontal) {
                val viewport = Rectangle(0, bounds.y, limit, bounds.height)
                rects = PillHosting.pillRects(bounds, lead.map { it.length }, trail.map { it.length }, viewport)
            }
            else {
                if (bounds.isEmpty) continue
                val viewport = Rectangle(bounds.x, 0, bounds.width, limit)
                val heights = { l: List<SpaceItem> -> l.map { if (it.header != null) bounds.height else it.length } }
                val blocks = PillRows.blocks(bounds, heights(lead), heights(trail))
                rects = items.zip(blocks).map { (item, block) ->
                    val full = if (item.header != null) Rectangle(block.x, block.y, minOf(item.length, block.width), block.height) else block
                    PillHosting.PillRect(full, clip(full.intersection(viewport)), clip(block.intersection(viewport)))
                }
            }
            for ((item, r) in items.zip(rects)) {
                if (item.header != null) result[item.header] = r else spacer = r.visible
            }
        }
        // No label to host these pills: every tab is collapsed (they sit in the otherwise empty strip), or only pinned tabs are left
        // beside a separate pinned row (they sit in the spare row next to it, see SpareRow).
        val loose = layout.looseItems()
        if (loose.isNotEmpty()) {
            val strip = spareRow() ?: emptyStrip(position)
            var at = if (horizontal) strip.x else strip.y
            val cross = if (horizontal) strip.height else stripThickness(false)
            val rowHeight = JBUI.scale(28)
            val all = Rectangle(0, 0, tabs.width, tabs.height)
            for (item in loose) {
                val length = if (horizontal || item.header == null) item.length else rowHeight
                val full = if (horizontal) Rectangle(at, strip.y, item.length, cross) else Rectangle(strip.x, at, item.length, length)
                val row = if (horizontal) full else Rectangle(strip.x, at, strip.width, length)
                at += if (horizontal) item.length else length
                val r = PillHosting.PillRect(full, clip(full.intersection(all)), clip(row.intersection(all)))
                if (item.header != null) result[item.header] = r else spacer = r.visible
            }
        }
        return Areas(result, spacer)
    }

    private fun clip(r: Rectangle): Rectangle = if (r.width <= 0 || r.height <= 0) Rectangle() else r

    /** The pills' slots inside their host labels and where they meet the strip's border line, for [PillAwareTabsBorder]. */
    fun pillCuts(): List<PillCut> {
        if (!alive() || tabs.isHideTabs || tabs.isEmptyVisible) return emptyList()
        val areas = pillAreas()
        val t = tabs.borderThickness
        val selected = tabs.selectedInfo?.let { tabs.getTabLabel(it) }
        val result = ArrayList<PillCut>()
        for (a in layout.assignments) {
            val label = a.label ?: continue
            val full = areas[a.header]?.full ?: continue
            val lb = label.bounds
            val slot = full.intersection(lb)
            if (slot.width <= 0 || slot.height <= 0) continue
            val edge = when (tabs.tabsPosition) {
                JBTabsPosition.top -> Rectangle(lb.x, lb.y + lb.height - t, lb.width, t)
                JBTabsPosition.bottom -> Rectangle(lb.x, lb.y, lb.width, t)
                JBTabsPosition.left -> Rectangle(lb.x + lb.width - t, lb.y, t, lb.height)
                JBTabsPosition.right -> Rectangle(lb.x, lb.y, t, lb.height)
            }
            val band = slot.intersection(edge).let { if (it.width <= 0 || it.height <= 0) Rectangle() else it }
            result += PillCut(slot, band, label === selected)
        }
        // the slot of a dragged tab or group shows no selection either (its label is still the selected one underneath)
        val t2 = t
        for (run in preview.gap.currentRuns()) {
            val edge = when (tabs.tabsPosition) {
                JBTabsPosition.top -> Rectangle(run.x, run.y + run.height - t2, run.width, t2)
                JBTabsPosition.bottom -> Rectangle(run.x, run.y, run.width, t2)
                JBTabsPosition.left -> Rectangle(run.x + run.width - t2, run.y, t2, run.height)
                JBTabsPosition.right -> Rectangle(run.x, run.y, t2, run.height)
            }
            result += PillCut(run, edge.intersection(run).let { if (it.width <= 0 || it.height <= 0) Rectangle() else it }, true)
        }
        return result
    }

    /**
     * The visible units of the strip in the order the user sees them (left to right; top to bottom in Left / Right; row by row
     * when the strip wraps): tabs and group pills. A pill comes before its group's tabs; a collapsed group is one
     * [StripUnit.Pill] with `collapsed = true` at the place of the group (the pill of an expanded group is hosted by its first
     * member, the pill of a collapsed group by the tab that follows its place, or by the last tab; both are listed at their
     * visual place, not inside the host). A tab's rect excludes the pill space reserved in its label. Rects are in tab-component
     * coordinates and cut to the visible part of the strip; units scrolled out of view are listed with an empty rect.
     * Tab groups use the drag preview.
     *
     * Reads the labels' bounds as they are now, without validating (also usable while a layout pass is running).
     */
    fun units(): List<StripUnit> {
        val areas = pillAreas()
        val horizontal = tabs.isHorizontalTabs
        val limitOf = limitFunction(horizontal)
        val groups = groups()
        fun pill(a: PillAssignment): StripUnit.Pill {
            val collapsed = groups.firstOrNull { it.id == a.header.groupId }?.collapsed ?: false
            return StripUnit.Pill(a.header.groupId, Rectangle(areas[a.header]?.row ?: Rectangle()), collapsed, a.header)
        }
        val hosted = IdentityHashMap<TabLabel, MutableList<PillAssignment>>()
        for (a in layout.assignments) a.label?.let { hosted.getOrPut(it) { ArrayList() } += a }
        val units = ArrayList<StripUnit>()
        for (info in tabs.getVisibleInfos()) {
            val file = info.`object` as? VirtualFile ?: continue
            val label = tabs.getTabLabel(info) ?: continue
            val mine = hosted[label].orEmpty()
            for (a in mine) if (a.leading) units += pill(a)
            val b = label.bounds
            var own = Rectangle()
            if (!b.isEmpty) {
                val (lead, trail) = layout.space.reserved(label)
                val limit = limitOf(b)
                val viewport = if (horizontal) Rectangle(0, b.y, limit, b.height) else Rectangle(b.x, 0, b.width, limit)
                val cut = Rectangle(b.x + lead, b.y, b.width - lead - trail, b.height).intersection(viewport)
                if (cut.width > 0 && cut.height > 0) own = cut
            }
            units += StripUnit.Tab(file, own, preview.effectiveGroupId(file, groups), info.isPinned)
            for (a in mine) if (!a.leading) units += pill(a)
        }
        for (a in layout.assignments) if (a.label == null) units += pill(a)
        return units
    }
}
