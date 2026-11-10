package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * The empty slot the platform shows in the tab strip for a tab that is being dragged: what `TabLabel.paint` draws for the drop
 * target of a drag session (`TabLabel.kt:321-339`): `JBUI.CurrentTheme.DragAndDrop.Area.BACKGROUND` over the strip, as a rounded
 * rectangle inset by 6 px at the top and 7 px at the bottom in Islands (`InternalUICustomization.isRoundedTabDuringDrag`,
 * arc `JBUI.CurrentTheme.MainToolbar.Button.hoverArc()`), otherwise the whole label rectangle.
 *
 * Why a component of our own and not the platform's drop-target state (`JBTabsImpl.dropInfo`, protected): the platform paints the
 * *whole* label, including the space a pill reserved in it, and layouts treat a drop target specially. This component covers exactly
 * the rectangles it is given (the dragged tab's own area, or a whole group: pill and tabs) and nothing else. It is a child of the tab
 * component, above the tab labels and below the pills (see `PillHeaders.raise`), invisible to the mouse.
 *
 * Wraps: `JBTabsImpl.tabPainter` (strip background), and asks [PlatformInternals.isRoundedTabDuringDrag] for the Islands look. EDT only.
 */
internal class DragGap(private val tabs: JBTabsImpl) : JComponent() {
    private var runs: List<Rectangle> = emptyList()

    init {
        isOpaque = false
        isFocusable = false
        putClientProperty("Layout.done", true)
    }

    override fun contains(x: Int, y: Int): Boolean = false

    /** Shows the gap over [newRuns] (tab-bar coordinates); empty hides it. */
    fun setRuns(newRuns: List<Rectangle>) {
        if (runs == newRuns) return
        val old = bounds
        runs = newRuns.map { Rectangle(it) }
        var union: Rectangle? = null
        for (r in runs) union = union?.union(r) ?: Rectangle(r)
        val now = union ?: Rectangle()
        if (bounds != now) bounds = now
        val parent = parent
        if (parent != null) {
            parent.repaint(old.x, old.y, old.width, old.height)
            parent.repaint(now.x, now.y, now.width, now.height)
        }
    }

    val isShowingGap: Boolean get() = runs.isNotEmpty()

    /** The rectangles the slot covers now (tab-bar coordinates). */
    fun currentRuns(): List<Rectangle> = runs

    override fun paintComponent(g: Graphics) {
        if (runs.isEmpty()) return
        val g2 = g.create() as Graphics2D
        try {
            g2.translate(-x, -y)
            val rounded = PlatformInternals.isRoundedTabDuringDrag()
            for (r in runs) {
                val part = g2.create() as Graphics2D
                try {
                    part.clipRect(r.x, r.y, r.width, r.height)
                    // the label underneath is painted normally; cover it with the strip, then draw the slot
                    tabs.tabPainter.fillBackground(tabs, part, r)
                    part.color = JBUI.CurrentTheme.DragAndDrop.Area.BACKGROUND
                    if (rounded) {
                        part.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        val arc = JBUI.CurrentTheme.MainToolbar.Button.hoverArc().get()
                        val top = JBUI.scale(6)
                        val bottom = top * 2 + JBUI.scale(1)
                        part.fillRoundRect(r.x, r.y + top, r.width, r.height - bottom, arc, arc)
                    }
                    else {
                        part.fillRect(r.x, r.y, r.width, r.height)
                    }
                }
                finally {
                    part.dispose()
                }
            }
        }
        finally {
            g2.dispose()
        }
    }
}
