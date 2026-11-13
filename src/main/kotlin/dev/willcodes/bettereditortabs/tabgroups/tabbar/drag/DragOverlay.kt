package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.ui.JBColor
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StartupUiUtil
import dev.willcodes.bettereditortabs.tabgroups.model.strip.HeaderDragGeometry
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

/**
 * Feedback for a drag: the floating picture follows the pointer and an outline (merging a group into another split) shows where it
 * will land. The picture is shown in a window of its own ([GhostWindow], like the platform's) except on Wayland, where it is painted
 * here; the outline is always painted here.
 *
 * Why a component in the layered pane and not a glass-pane `Painter`: `IdeGlassPaneUtil.installPainter` is racy. Its `Activatable`
 * calls `showNotify` once directly and again for every deferred `SHOWING_CHANGED` hierarchy event; each extra `showNotify` disposes
 * the previous registration, whose disposer queues `removePainter(painter)` with `invokeLater` (`IdeGlassPaneImpl.addPainter`), and
 * that queued removal runs after the painter was just added again. The painter silently ends up unregistered. This overlay does
 * not depend on any of that: it is a plain non-opaque component in `JLayeredPane.DRAG_LAYER` of the frame's root pane, present only
 * while something is shown, and invisible to the mouse (`contains` is false).
 *
 * Everything is kept in screen coordinates because a drag can end in another split or frame; each frame's overlay draws the parts
 * that fall into it. Only the old and new rectangles are repainted, so no stale pixels remain. EDT only.
 */
internal class DragOverlay(private val owner: JComponent) : JComponent() {
    private var host: JLayeredPane? = null

    private var ghost: BufferedImage? = null
    private var ghostSize = Dimension()
    private var ghostLocation = Point()

    /** Insertion bar or outline, screen coordinates. */
    private var marker: Rectangle? = null
    private var markerOutline = false

    init {
        isOpaque = false
        isEnabled = false
    }

    override fun contains(x: Int, y: Int): Boolean = false

    companion object {
        /** Opacity of the floating picture: the platform shows its drag image window with alpha ratio 0.1. */
        const val GHOST_OPACITY = 0.9f

        /**
         * A picture of [rect] (tab-bar coordinates): [background] (or the tab painter's background), then each piece (a pill or a tab
         * label) painted on its own at its bounds. Painting the pieces and not a clipped `tabs.paint()` keeps neighbouring tabs and
         * the editor out of the picture; the image comes from [ImageUtil] so that it has the screen's scale (HiDPI).
         */
        fun snapshot(tabs: JBEditorTabs, rect: Rectangle, pieces: List<Pair<JComponent, Rectangle>>, background: Color? = null): BufferedImage? {
            if (rect.isEmpty) return null
            val image = ImageUtil.createImage(tabs.graphicsConfiguration, rect.width, rect.height, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.color = background ?: try {
                    tabs.tabPainter.getBackgroundColor()
                }
                catch (e: Exception) {
                    tabs.background
                }
                g.fillRect(0, 0, rect.width, rect.height)
                for ((component, bounds) in pieces) {
                    val at = HeaderDragGeometry.inPicture(bounds, rect)
                    val child = g.create(at.x, at.y, at.width, at.height) as Graphics2D
                    try {
                        component.paint(child)
                    }
                    finally {
                        child.dispose()
                    }
                }
            }
            finally {
                g.dispose()
            }
            return image
        }
    }

    /**
     * The floating picture. Shown in the platform's kind of drag window ([GhostWindow]) wherever a window can be placed on screen;
     * on Wayland it is painted into this overlay, like the platform's glass-pane view.
     */
    fun setGhost(image: BufferedImage?, size: Dimension) = setGhost(image, size, false)

    /**
     * Like [setGhost]; [ownFrame] says the picture has a frame of its own (a group chip with rounded corners and a border): then no
     * background and no corner clip is added around it, neither in the window ([GhostWindow.show]) nor in the frame (Wayland).
     */
    fun setGhost(image: BufferedImage?, size: Dimension, ownFrame: Boolean) {
        ghostOwnFrame = ownFrame
        if (!inFrame) {
            windowGhost = image
            ghostSize = Dimension(size)
            if (image == null) GhostWindow.hide(this)
            else if (windowGhostShown) GhostWindow.show(this, SwingUtilities.getWindowAncestor(owner), image, ghostLocation, ownFrame)
            if (image == null) windowGhostShown = false
            return
        }
        update {
            ghost = image
            ghostSize = Dimension(size)
        }
    }

    fun moveGhost(screenTopLeft: Point) {
        if (!inFrame) {
            ghostLocation = Point(screenTopLeft)
            val image = windowGhost ?: return
            if (windowGhostShown) GhostWindow.move(this, ghostLocation)
            else GhostWindow.show(this, SwingUtilities.getWindowAncestor(owner), image, ghostLocation, ghostOwnFrame)
            windowGhostShown = true
            return
        }
        update { ghostLocation = Point(screenTopLeft) }
    }

    /** The picture in a window of its own (everywhere but Wayland). */
    private val inFrame: Boolean get() = StartupUiUtil.isWaylandToolkit()
    private var windowGhost: BufferedImage? = null
    private var windowGhostShown = false
    private var ghostOwnFrame = false

    private fun hideWindowGhost() {
        windowGhost = null
        windowGhostShown = false
        GhostWindow.hide(this)
    }

    fun setMarker(screenRect: Rectangle?, outline: Boolean = false) {
        if (marker == screenRect && markerOutline == outline) return
        update {
            marker = screenRect?.let { Rectangle(it) }
            markerOutline = outline
        }
    }

    fun clear() {
        hideWindowGhost()
        update {
            ghost = null
            marker = null
        }
    }

    /** Detaches the overlay from the layered pane. */
    fun dispose() {
        hideWindowGhost()
        ghost = null
        marker = null
        detach(emptyList())
    }

    private fun update(change: () -> Unit) {
        val before = dirtyRects()
        change()
        val after = dirtyRects()
        if (ghost == null && marker == null) {
            detach(before)
            return
        }
        if (!attach()) return
        for (r in before + after) repaint(r.x, r.y, r.width, r.height)
    }

    private fun attach(): Boolean {
        val layered = owner.rootPane?.layeredPane ?: return false
        if (host !== layered) {
            detach(emptyList())
            layered.add(this, JLayeredPane.DRAG_LAYER as Any)
            host = layered
        }
        if (bounds != Rectangle(0, 0, layered.width, layered.height)) setBounds(0, 0, layered.width, layered.height)
        return true
    }

    private fun detach(dirty: List<Rectangle>) {
        val layered = host ?: return
        host = null
        layered.remove(this)
        for (r in dirty) layered.repaint(r.x, r.y, r.width, r.height)
    }

    /** Screen rectangle to overlay-local coordinates; null if the layered pane is not on screen. */
    private fun local(screen: Rectangle): Rectangle? {
        val layered = host ?: owner.rootPane?.layeredPane ?: return null
        if (!layered.isShowing) return null
        val origin = layered.locationOnScreen
        return Rectangle(screen.x - origin.x, screen.y - origin.y, screen.width, screen.height)
    }

    private fun dirtyRects(): List<Rectangle> {
        val margin = JBUI.scale(4)
        val result = ArrayList<Rectangle>(2)
        if (ghost != null) {
            local(Rectangle(ghostLocation, ghostSize))?.let { result += it.also { r -> r.grow(margin, margin) } }
        }
        marker?.let { m -> local(m)?.let { result += it.also { r -> r.grow(margin, margin) } } }
        return result
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val accent = JBColor.namedColor("Component.focusColor", JBColor(0x3574F0, 0x548AF7))
            ghost?.let { img ->
                local(Rectangle(ghostLocation, ghostSize))?.let { r ->
                    val ghostGraphics = g2.create() as Graphics2D
                    try {
                        // The look of the platform's own drag image (DockManagerImpl.DragImageDialog): the tab picture at 90 % opacity
                        // (alpha mode ratio 0.1, DockManagerImpl.kt:343-348), with rounded corners in Islands; no outline.
                        ghostGraphics.composite = AlphaComposite.SrcOver.derive(GHOST_OPACITY)
                        if (!ghostOwnFrame && PlatformInternals.isRoundedTabDuringDrag()) {
                            val arc = JBUI.scale(8)
                            ghostGraphics.clip = RoundRectangle2D.Float(r.x.toFloat(), r.y.toFloat(), r.width.toFloat(), r.height.toFloat(), arc.toFloat(), arc.toFloat())
                        }
                        // the platform's picture is transparent but for icon and name; its window is what shows behind them
                        // (a picture with a frame of its own, a group chip, is ARGB with transparent corners: nothing behind it)
                        if (!ghostOwnFrame) {
                            ghostGraphics.color = DragImages.windowBackground()
                            ghostGraphics.fillRect(r.x, r.y, r.width, r.height)
                        }
                        ghostGraphics.drawImage(img, r.x, r.y, r.width, r.height, null)
                    }
                    finally {
                        ghostGraphics.dispose()
                    }
                }
            }
            marker?.let { m ->
                local(m)?.let { r ->
                    g2.color = accent
                    if (markerOutline) {
                        g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
                        g2.drawRoundRect(r.x, r.y, r.width, r.height, JBUI.scale(8), JBUI.scale(8))
                    }
                    else {
                        g2.fillRoundRect(r.x, r.y, r.width, r.height, JBUI.scale(3), JBUI.scale(3))
                    }
                }
            }
        }
        finally {
            g2.dispose()
        }
    }
}
