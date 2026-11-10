package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.ui.tabs.impl.TabLabel
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PillHosting
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import java.awt.Rectangle
import javax.swing.JComponent

/**
 * The header components ("pills") of one split: creates and removes them as the groups change, places them over the space reserved
 * in their host labels, and keeps them above the labels in z-order.
 *
 * Each header is a direct child of the tab component (never inside a tab label, so that the platform's `DragHelper` does not mistake
 * a press on it for a tab drag). Internal APIs wrapped: the tab component's child list and z-order (`JBTabsImpl.component`),
 * `JBTabsImpl.getVisibleInfos` / `getTabLabel`. EDT only.
 */
internal class PillHeaders(
    private val tabs: JBEditorTabs,
    private val isPlatformDragActive: () -> Boolean,
    private val onHeaderPaint: () -> Unit,
    private val platformDraggedLabel: () -> TabLabel?,
) {
    private val byGroup = LinkedHashMap<String, GroupHeader>()

    /** Receives the gestures of every header; setting it re-targets the existing headers. */
    var listener: StripEvents? = null
        set(value) {
            field = value
            byGroup.values.forEach { it.listener = value }
        }

    val all: List<GroupHeader> get() = byGroup.values.toList()

    fun of(groupId: String): GroupHeader? = byGroup[groupId]

    /** Creates the headers of new groups, removes those of vanished ones and gives every header its decoration. */
    fun sync(groups: List<GroupDecoration>) {
        val ids = groups.map { it.id }.toSet()
        for ((id, header) in byGroup.toList()) {
            if (id !in ids) {
                tabs.remove(header)
                byGroup.remove(id)
            }
        }
        for (g in groups) {
            val header = byGroup.getOrPut(g.id) {
                GroupHeader(g.id).also {
                    it.transparentToHitTest = isPlatformDragActive
                    it.onPaint = onHeaderPaint
                    tabs.add(it)
                }
            }
            header.listener = listener
            header.decoration = g
        }
    }

    /** Hides (paints nothing for) the pills of [groupIds], the groups being dragged; every other pill is shown. */
    fun suppress(groupIds: Set<String>) {
        for ((id, header) in byGroup) header.suppressed = id in groupIds
    }

    /** Places the pill components over the space reserved in their host labels. [dragGap] is raised along (see [raise]). */
    fun place(areas: Map<GroupHeader, PillHosting.PillRect>, dragGap: JComponent) {
        val placed = HashSet<GroupHeader>()
        var changed = false
        for ((header, r) in areas) {
            header.setFullArea(r.full.width, r.full.height, r.offsetX, r.offsetY)
            if (header.bounds != r.visible) {
                header.bounds = r.visible
                changed = true
            }
            placed += header
        }
        for (header in byGroup.values) if (header !in placed && !header.bounds.isEmpty) {
            header.bounds = Rectangle()
            changed = true
        }
        raise(dragGap)
        if (changed) tabs.repaint()
    }

    /**
     * The platform moves the selected label to the front of the z-order (`JBTabsImpl.select`), which would hide a pill hosted
     * by it. Pills are kept above every label, except the label that is being dragged, which stays above everything. The drop
     * slot [dragGap] goes above every label and below every pill.
     */
    fun raise(dragGap: JComponent) {
        val labels = tabs.getVisibleInfos().mapNotNull { tabs.getTabLabel(it) }.filter { it.parent === tabs.component }
        if (labels.isEmpty()) return
        val draggedLabel = platformDraggedLabel()?.takeIf { it.parent === tabs.component }
        for (header in byGroup.values) {
            if (header.parent !== tabs.component) continue
            val current = tabs.component.getComponentZOrder(header)
            if (draggedLabel != null) {
                val d = tabs.component.getComponentZOrder(draggedLabel)
                val target = if (current < d) d else d + 1
                if (current != target) tabs.component.setComponentZOrder(header, target)
            }
            else {
                val top = labels.minOf { tabs.component.getComponentZOrder(it) }
                if (current > top) tabs.component.setComponentZOrder(header, top)
            }
        }
        if (dragGap.parent === tabs.component) {
            val top = labels.minOf { tabs.component.getComponentZOrder(it) }
            val z = tabs.component.getComponentZOrder(dragGap)
            val lowestPill = byGroup.values.filter { it.parent === tabs.component }.maxOfOrNull { tabs.component.getComponentZOrder(it) } ?: -1
            if (z > top || z < lowestPill) tabs.component.setComponentZOrder(dragGap, top)
        }
    }

    fun dispose() {
        byGroup.values.forEach { tabs.remove(it) }
        byGroup.clear()
    }
}
