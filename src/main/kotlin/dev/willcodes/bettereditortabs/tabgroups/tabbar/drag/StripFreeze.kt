package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.StartupUiUtil
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

/**
 * A still picture of a tab strip, shown over it while a multi-step change runs that the user must only see the end of.
 *
 * Why: moving a group to another split ([moveGroupToSplit]) closes and opens editors one by one, and the platform's
 * `closeFile` / `openFile` let the event queue run in between (their `fileClosed` / `fileOpened` events are dispatched during the move),
 * so Swing paints the strips half-way: the target with its drop gap still open and the moved tabs arriving, the source with tabs
 * missing. That showed as a brief glitch next to the drop place, looking like a collapse animation. The picture hides those frames.
 *
 * The picture covers [area] (the strip's rows, in the tab component's coordinates) from the root pane's `DRAG_LAYER`, like the drag
 * overlay ([DragOverlay]); [release] removes it after validating the strip, so the next paint shows the final layout. EDT only.
 */
internal class StripFreeze private constructor(
    private val layered: JLayeredPane,
    private val shot: JComponent,
    private val strip: JComponent,
) {
    /** Lays the strip out and removes the picture; the strip repaints with its final state. */
    fun release() {
        strip.validate()
        val r = shot.bounds
        layered.remove(shot)
        layered.repaint(r.x, r.y, r.width, r.height)
    }

    companion object {
        /** A picture of [area] of [strip], shown at once; null if the strip is not showing (nothing to hide then). */
        fun over(strip: JComponent, area: Rectangle): StripFreeze? {
            if (!strip.isShowing || area.isEmpty) return null
            val layered = SwingUtilities.getRootPane(strip)?.layeredPane ?: return null
            val r = area.intersection(Rectangle(0, 0, strip.width, strip.height))
            if (r.isEmpty) return null
            val image = ImageUtil.createImage(strip.graphicsConfiguration, r.width, r.height, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            try {
                g.translate(-r.x, -r.y)
                g.clipRect(r.x, r.y, r.width, r.height)
                strip.paint(g)
            }
            finally {
                g.dispose()
            }
            val shot = object : JComponent() {
                override fun paintComponent(g: Graphics) = StartupUiUtil.drawImage(g, image, 0, 0, null)
            }
            shot.isOpaque = true
            shot.bounds = SwingUtilities.convertRectangle(strip, r, layered)
            layered.add(shot, JLayeredPane.DRAG_LAYER as Any)
            shot.paintImmediately(0, 0, shot.width, shot.height)
            return StripFreeze(layered, shot, strip)
        }
    }
}
