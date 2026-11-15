package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/**
 * POP-4 (rule preview, user request 2026-11-14): the outline in the group's colour around the tabs an auto-group rule would match, in one
 * split's strip. A child of the tab component above the tab labels (and so above the label the platform moves to the front when a tab is
 * selected: [update] puts it first in the z-order again after every layout pass), invisible to the mouse, like [DragGap]. It paints
 * nothing but the outlines: tint, selection rendering, pill slots and drags are untouched.
 *
 * Shape: where the platform draws a tab as a rounded rectangle (Islands, [PlatformInternals.isRoundedTabDuringDrag]) the same rectangle
 * as [DragGap] uses (inset 6 px at the top, 7 px at the bottom, arc `JBUI.CurrentTheme.MainToolbar.Button.hoverArc()`), otherwise the
 * tab's own rectangle (what the classic New UI theme paints the tab in). The stroke runs inside it, so it never covers a neighbour.
 *
 * The rectangles come from the strip's units ([TabBarAdapter.stripUnits]), so a tab hidden by a collapsed group has none, a tab scrolled
 * out of view has an empty one and the reserved space of a pill is not part of the tab. EDT only.
 */
internal class RuleOutline(private val tabs: JBEditorTabs) : JComponent() {
    private var files: Set<VirtualFile> = emptySet()
    private var color: Color? = null
    private var rects: List<Rectangle> = emptyList()

    init {
        isOpaque = false
        isFocusable = false
        putClientProperty("Layout.done", true) // JBTabsImpl.LAYOUT_DONE: not reset to 0x0 by the platform's layout
    }

    override fun contains(x: Int, y: Int): Boolean = false

    /** True while some file is outlined. */
    val isActive: Boolean get() = files.isNotEmpty() && color != null

    /** The files outlined now (tests). */
    fun outlined(): Set<VirtualFile> = if (isActive) files else emptySet()

    /** The rectangles drawn now, tab component coordinates (tests). */
    fun shown(): List<Rectangle> = rects

    /** Replaces what is outlined; an empty set or a null colour clears it. */
    fun set(newFiles: Collection<VirtualFile>, newColor: Color?) {
        files = if (newColor == null) emptySet() else newFiles.toHashSet()
        color = newColor
        if (!isActive) rects = emptyList()
    }

    /**
     * Lays the overlay over the tabs of the outlined files: [tabRects] is the strip's visible tabs, file and own rectangle (empty = scrolled
     * out). [skip]: a tab that is being dragged (its place is the drop slot). Keeps the overlay first in the z-order. Call after every
     * layout pass while [isActive] and once more when it stops being.
     */
    private var wasActive = false

    /**
     * Re-places the outlines after a layout pass: [tabRects] gives the visible tabs' rectangles (read only while an outline shows or
     * has just been cleared); none while [stripShown] is false (placement None). [skip] is the tab being dragged.
     */
    fun refresh(stripShown: Boolean, skip: VirtualFile?, tabRects: () -> List<Pair<VirtualFile, Rectangle>>) {
        if (!isActive && !wasActive) return
        wasActive = isActive
        update(if (isActive && stripShown) tabRects() else emptyList(), skip)
    }

    fun update(tabRects: List<Pair<VirtualFile, Rectangle>>, skip: VirtualFile?) {
        val wanted = if (!isActive) emptyList() else tabRects.filter { (f, r) -> f in files && f != skip && !r.isEmpty }.map { Rectangle(it.second) }
        var union: Rectangle? = null
        for (r in wanted) union = union?.union(r) ?: Rectangle(r)
        val now = union ?: Rectangle()
        val old = bounds
        rects = wanted
        if (bounds != now) {
            bounds = now
            tabs.component.repaint(old.x, old.y, old.width, old.height)
        }
        tabs.component.repaint(now.x, now.y, now.width, now.height)
        if (parent === tabs.component && !now.isEmpty && tabs.component.getComponentZOrder(this) != 0) tabs.component.setComponentZOrder(this, 0)
    }

    /**
     * Fix round 2 (user request 2026-11-14, bug 4): the platform draws the selected tab's indicator (and the Islands outline) in the
     * border pass that ends `paintChildren`, over every child of the tab component, so a child that paints the outline is covered by it.
     * With the border hook installed ([PillAwareTabsBorder], which calls [paintAfterChildren] at the end of each of its passes) the
     * outline is therefore painted from there: this component, painted last of the children, only notes that the outlines are due.
     * False (the default, and when the border could not be wrapped) = the component paints them itself, under the indicator.
     */
    var paintsAfterBorder = false

    /** The component was painted since the last border pass and has outlines to draw. */
    private var due = false

    /**
     * Called by the strip's border wrapper at the end of every pass of the platform's border painting, in tab component coordinates. Draws
     * the outlines if the component has just been painted (so only the pass after the children, which is the one that paints the selected
     * tab's indicator); the pass in front of the children finds nothing due.
     */
    fun paintAfterChildren(g: Graphics) {
        if (!due) return
        due = false
        if (!paintsAfterBorder) return
        val g2 = g.create() as Graphics2D
        try {
            drawOutlines(g2)
        }
        finally {
            g2.dispose()
        }
    }

    override fun paintComponent(g: Graphics) {
        if (paintsAfterBorder) {
            due = isActive && rects.isNotEmpty()
            return
        }
        val g2 = g.create() as Graphics2D
        try {
            g2.translate(-x, -y)
            drawOutlines(g2)
        }
        finally {
            g2.dispose()
        }
    }

    /** Draws the outlines, [g2] in tab component coordinates. */
    private fun drawOutlines(g2: Graphics2D) {
        val c = color ?: return
        if (rects.isEmpty()) return
        run {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val stroke = JBUI.scale(2).toFloat()
            g2.stroke = BasicStroke(stroke)
            g2.color = c
            val rounded = PlatformInternals.isRoundedTabDuringDrag()
            val half = stroke / 2f
            for (r in rects) {
                if (rounded) {
                    val arc = JBUI.CurrentTheme.MainToolbar.Button.hoverArc().get().toFloat()
                    val top = JBUI.scale(6)
                    val bottom = top * 2 + JBUI.scale(1)
                    g2.draw(RoundRectangle2D.Float(r.x + half, r.y + top + half, r.width - stroke, r.height - bottom - stroke, arc, arc))
                }
                else {
                    g2.draw(RoundRectangle2D.Float(r.x + half, r.y + half, r.width - stroke, r.height - stroke, 0f, 0f))
                }
            }
        }
    }
}
