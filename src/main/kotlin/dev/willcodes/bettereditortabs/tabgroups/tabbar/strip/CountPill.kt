package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PartialExpand
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.StripStyle
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * The "+N" count of a partly shown group (COLL-8): N is how many of the group's tabs are still hidden. A small rounded pill like the
 * count of a group drag's picture (INT-6, [PillPainter.paintCount]): the group colour mixed into the strip background, with the text in
 * the colour of better contrast.
 *
 * Hosted like a header ([GroupHeader]): a direct child of the tab component, its space reserved in the trailing side of the peek's label
 * in Top / Bottom strips (`PillLayout`, HDR-6) or a row of its own right below the peek in Left / Right strips (`VerticalRows`, `StripGeometry`); this component
 * only paints and reports clicks. A click, a middle click and a right click are the header's (the same [StripEvents] calls with the
 * group's [GroupHeader], [headerOf]); there is no hover list (INT-5).
 */
internal class CountPill(val groupId: String, private val style: () -> StripStyle = { StripStyle.DEFAULT }) : JComponent() {
    /** N of "+N". */
    var count: Int = 0
        private set
    private var groupColor: Color = Color.GRAY

    /** The group's header: the event source of this pill's gestures. Set by [CountPills]. */
    internal var headerOf: () -> GroupHeader? = { null }
    var listener: StripEvents? = null

    /** While true the pill does not take part in hit testing (a platform tab drag looks for the label under the dragged tab). */
    internal var transparentToHitTest: () -> Boolean = { false }

    /** Paints the plain strip background into the given rectangle (see [GroupHeader.stripBackground]). */
    internal var stripBackground: ((Graphics2D, Rectangle) -> Unit)? = null

    private var fullWidth = 0
    private var fullHeight = 0
    private var offsetX = 0
    private var offsetY = 0

    init {
        isOpaque = false
        // Marker understood by JBTabsImpl.applyResetComponents: "this component has been laid out", keep our bounds.
        putClientProperty("Layout.done", true)
        CountMouseHandler(this).also {
            addMouseListener(it)
            addMouseMotionListener(it)
        }
    }

    /** Shows [n] for group [decoration] (its colour). */
    fun set(decoration: GroupDecoration, n: Int) {
        if (count == n && groupColor == decoration.color) return
        count = n
        groupColor = decoration.color
        toolTipText = description
        repaint()
    }

    /** The text of the pill: "+N". */
    internal val text: String get() = BetterEditorTabsBundle.message("strip.count", count.toString())

    /** The accessible name and tooltip: "N more tabs". */
    internal val description: String get() = BetterEditorTabsBundle.message("strip.count.tooltip", count)

    /** Screen readers read "N more tabs" ([description]) for the pill. */
    override fun getAccessibleContext(): javax.accessibility.AccessibleContext {
        if (accessibleContext == null) {
            accessibleContext = object : AccessibleJComponent() {
                override fun getAccessibleRole(): javax.accessibility.AccessibleRole = javax.accessibility.AccessibleRole.LABEL
                override fun getAccessibleName(): String = description
            }
        }
        return accessibleContext
    }

    private fun pillHeight(rowHeight: Int): Int =
        PillPainter.namedHeight(getFontMetrics(PillPainter.font()), rowHeight - 2 * JBUI.scale(V_INSET))

    /** The length along the strip the pill needs, with a small gap on both sides (the space reserved in the peek's label). */
    fun slotLength(): Int {
        val fm = getFontMetrics(PillPainter.font())
        val h = PillPainter.namedHeight(fm, JBUI.scale(28) - 2 * JBUI.scale(V_INSET))
        return PillPainter.countWidth(fm, text, h) + JBUI.scale(2 * GAP)
    }

    override fun getPreferredSize(): Dimension = Dimension(slotLength(), JBUI.scale(28))

    override fun contains(x: Int, y: Int): Boolean = !transparentToHitTest() && super.contains(x, y)

    /** The pill's whole slot and how far its visible part ([getBounds]) is shifted into it; paints cut, not squeezed. */
    fun setFullArea(width: Int, height: Int, offsetX: Int, offsetY: Int) {
        if (fullWidth == width && fullHeight == height && this.offsetX == offsetX && this.offsetY == offsetY) return
        fullWidth = width
        fullHeight = height
        this.offsetX = offsetX
        this.offsetY = offsetY
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val w0 = if (fullWidth > 0) fullWidth else width
        val h0 = if (fullHeight > 0) fullHeight else height
        val g2 = g.create() as Graphics2D
        try {
            stripBackground?.invoke(g2, Rectangle(0, 0, width, height))
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.translate(-offsetX, -offsetY)
            val gap = JBUI.scale(GAP).toDouble()
            val h = pillHeight(h0).toDouble()
            val background = parent?.background ?: UIUtil.getPanelBackground()
            PillPainter.paintCount(g2, groupColor, background, text, gap, (h0 - h) / 2, w0 - 2 * gap, h)
        }
        finally {
            g2.dispose()
        }
    }

    companion object {
        private const val GAP = 4
        private const val V_INSET = 4
    }
}

/** A click on the count is a click on the header (COLL-8): left, middle and right, reported on release within the click slop (INT-1). */
internal class CountMouseHandler(private val pill: CountPill) : MouseAdapter() {
    private var pressPoint: java.awt.Point? = null
    private var pressButton = 0

    override fun mousePressed(e: MouseEvent) {
        pressPoint = e.point
        pressButton = e.button
        e.consume()
    }

    override fun mouseReleased(e: MouseEvent) {
        val start = pressPoint
        pressPoint = null
        e.consume()
        if (start == null || e.button != pressButton) return
        if (start.distance(e.point) > HeaderMouseHandler.CLICK_SLOP) return
        val listener = pill.listener ?: return
        val header = pill.headerOf() ?: return
        when {
            e.isPopupTrigger || e.button == MouseEvent.BUTTON3 -> listener.headerRightClicked(header, e)
            e.button == MouseEvent.BUTTON2 -> listener.headerMiddleClicked(header, e)
            e.button == MouseEvent.BUTTON1 -> listener.headerClicked(header, e)
        }
    }

    override fun mouseClicked(e: MouseEvent) {
        e.consume()
    }

    override fun mouseDragged(e: MouseEvent) {
        e.consume()
    }
}

/**
 * The count pills of one split ([CountPill]): creates and removes them as the groups change, places them over the slots the strip
 * geometry computed and keeps them above the labels. A group has one while it shows a peek and has hidden tabs (COLL-8).
 */
internal class CountPills(
    private val tabs: JBEditorTabs,
    private val headerOf: (String) -> GroupHeader?,
    private val isPlatformDragActive: () -> Boolean,
    private val platformDraggedLabel: () -> com.intellij.ui.tabs.impl.TabLabel?,
    private val style: () -> StripStyle = { StripStyle.DEFAULT },
) {
    private val byGroup = LinkedHashMap<String, CountPill>()

    var listener: StripEvents? = null
        set(value) {
            field = value
            byGroup.values.forEach { it.listener = value }
        }

    val all: List<CountPill> get() = byGroup.values.toList()

    fun of(groupId: String): CountPill? = byGroup[groupId]

    /** The slot length the count of [groupId] reserves, 0 if the group has none. */
    fun lengthOf(groupId: String): Int = byGroup[groupId]?.slotLength() ?: 0

    /** Creates, updates and removes the count pills for [groups] (as the strip shows them; [open] are the files that have a tab). */
    fun sync(groups: List<GroupDecoration>, open: Set<com.intellij.openapi.vfs.VirtualFile>) {
        val wanted = LinkedHashMap<String, Pair<GroupDecoration, Int>>()
        for (g in groups) {
            val n = PartialExpand.hiddenCount(g.members, open, g.collapsed, g.peek)
            if (n > 0) wanted[g.id] = g to n
        }
        for ((id, pill) in byGroup.toList()) if (id !in wanted) {
            tabs.remove(pill)
            byGroup.remove(id)
        }
        for ((id, entry) in wanted) {
            val pill = byGroup.getOrPut(id) {
                CountPill(id, style).also {
                    it.transparentToHitTest = isPlatformDragActive
                    it.stripBackground = { g, r -> tabs.tabPainter.fillBackground(tabs, g, r) }
                    it.headerOf = { headerOf(id) }
                    it.listener = listener
                    tabs.add(it)
                }
            }
            val before = pill.slotLength()
            pill.set(entry.first, entry.second)
            if (pill.slotLength() != before) tabs.revalidate()
        }
    }

    /** Places the count pills over their slots ([areas] by group id); a pill with no area is not shown. */
    fun place(areas: Map<String, dev.willcodes.bettereditortabs.tabgroups.model.strip.PillHosting.PillRect>) {
        var changed = false
        for ((id, pill) in byGroup) {
            val r = areas[id]
            if (r == null) {
                if (!pill.bounds.isEmpty) {
                    pill.bounds = Rectangle()
                    changed = true
                }
                continue
            }
            pill.setFullArea(r.full.width, r.full.height, r.offsetX, r.offsetY)
            if (pill.bounds != r.visible) {
                pill.bounds = r.visible
                changed = true
            }
        }
        raise()
        if (changed) tabs.repaint()
    }

    /** Keeps the count pills above every label (the platform moves the selected label to the front), like [PillHeaders.raise]. */
    fun raise() {
        if (byGroup.isEmpty()) return
        val labels = tabs.getVisibleInfos().mapNotNull { tabs.getTabLabel(it) }.filter { it.parent === tabs.component }
        if (labels.isEmpty()) return
        val draggedLabel = platformDraggedLabel()?.takeIf { it.parent === tabs.component }
        for (pill in byGroup.values) {
            if (pill.parent !== tabs.component) continue
            val current = tabs.component.getComponentZOrder(pill)
            if (draggedLabel != null) {
                val d = tabs.component.getComponentZOrder(draggedLabel)
                val target = if (current < d) d else d + 1
                if (current != target) tabs.component.setComponentZOrder(pill, target)
            }
            else {
                val top = labels.minOf { tabs.component.getComponentZOrder(it) }
                if (current > top) tabs.component.setComponentZOrder(pill, top)
            }
        }
    }

    fun dispose() {
        byGroup.values.forEach { tabs.remove(it) }
        byGroup.clear()
    }
}
