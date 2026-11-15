package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.model.strip.GroupChipLayout
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.StripStyle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.PillPainter
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/**
 * The floating picture of a group drag (INT-6, for expanded and collapsed groups alike): a bordered, rounded chip that holds the
 * group's own pill, painted by the strip's [PillPainter] (same font, truncation by the current style, shape and colours), and after a
 * small gap the number of tabs being moved in a pill of its own: the group colour blended into the chip's background, so it is
 * lighter than the group's pill (user request 2026-11-13).
 *
 * The chip's background is the one of the platform's tab drag picture's window ([DragImages.windowBackground]) and its frame the
 * popup border colour (`JBUI.CurrentTheme.Popup.borderColor`); its height is that of the platform's tab picture. The corners outside
 * the rounded shape stay transparent: [GhostWindow] shows the picture in a transparent window (or fills the corners where the
 * platform cannot make one), so the chip's own shape is the visible frame. Layout arithmetic is in [GroupChipLayout].
 *
 * The count is a decoration's member count, not what the strip shows: the members of a collapsed group are hidden. EDT only.
 */
internal object GroupChipImage {
    private const val PAD_LEFT = 6
    private const val PAD_RIGHT = 6
    private const val GAP = 4

    private const val ARC = 8
    private const val PILL_V_INSET = 4
    private const val FALLBACK_HEIGHT = 28

    /** The chip for [decoration] moving [tabCount] tabs; null if it cannot be drawn (no usable size). */
    fun create(tabs: JBEditorTabs, decoration: GroupDecoration, tabCount: Int, style: StripStyle): BufferedImage? {
        val countText = BetterEditorTabsBundle.message("drag.group.tabCount", tabCount)
        val pillMetrics = tabs.getFontMetrics(PillPainter.font())
        val minHeight = DragImages.tabPictureHeight(tabs) ?: JBUI.scale(FALLBACK_HEIGHT)

        val isDot = decoration.name.isEmpty()
        val name = PillPainter.displayName(decoration.name, style.maxNameLength)
        val pillWidth = if (isDot) PillPainter.dotSize() else PillPainter.naturalWidth(pillMetrics, name, false)
        val pillHeight = if (isDot) PillPainter.dotSize() else PillPainter.namedHeight(pillMetrics, minHeight - 2 * JBUI.scale(PILL_V_INSET))

        // the count is a pill of its own, as tall as a named pill and never narrower than it is tall (a single digit is a rounded square)
        val countHeight = PillPainter.namedHeight(pillMetrics, minHeight - 2 * JBUI.scale(PILL_V_INSET))
        val countWidth = PillPainter.countWidth(pillMetrics, countText, countHeight)

        val border = 1
        val layout = GroupChipLayout.layout(
            pillWidth, pillHeight, countWidth, countHeight, minHeight,
            JBUI.scale(PAD_LEFT), JBUI.scale(PAD_RIGHT), JBUI.scale(GAP), border,
        )
        val w = layout.size.width
        val h = layout.size.height
        if (w <= 0 || h <= 0) return null

        val image = ImageUtil.createImage(tabs.graphicsConfiguration, w, h, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            // grayscale text: the picture is ARGB, sub-pixel text would blend against transparent pixels
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)

            val arc = JBUI.scale(ARC).toDouble()
            g.color = DragImages.windowBackground()
            g.fill(RoundRectangle2D.Double(0.0, 0.0, w.toDouble(), h.toDouble(), arc, arc))
            g.color = JBUI.CurrentTheme.Popup.borderColor(true)
            val half = border / 2.0
            g.draw(RoundRectangle2D.Double(half, half, w - border.toDouble(), h - border.toDouble(), arc, arc))

            val p = layout.pill
            if (isDot) PillPainter.paintDot(g, decoration.color, p.x.toDouble(), p.y.toDouble(), p.width.toDouble(), p.height.toDouble())
            else PillPainter.paintNamed(g, decoration.color, name, p.x.toDouble(), p.y.toDouble(), p.width.toDouble(), p.height.toDouble())

            // the count pill: the group colour, lighter (blended into the chip's background) so it reads as a companion, not a second group
            val t = layout.text
            PillPainter.paintCount(g, decoration.color, DragImages.windowBackground(), countText, t.x.toDouble(), t.y.toDouble(), t.width.toDouble(), t.height.toDouble())
        }
        finally {
            g.dispose()
        }
        return image
    }
}
