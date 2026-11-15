package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.model.groups.Contrast
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D

/**
 * The group pill and where its sizes live: one painter for the strip ([GroupHeader]) and for the floating picture of a group drag
 * (`GroupChipImage`), so the pill in the picture is the strip's pill by construction (same font, truncation, shape, colours).
 *
 * A group with a name is a rounded rectangle in the group's opaque colour (HDR-1) with the name in the colour of better contrast on
 * it (HDR-2, as the current theme resolves the fill); a group without a name is a plain dot (HDR-1). Sizes are in layout pixels
 * (scaled with `JBUI.scale`). Wraps `JBUI.CurrentTheme.EditorTabs.font()`. EDT only.
 */
internal object PillPainter {
    /** Space between the pill's edge and the name, each side (user request 2026-11-13: was 8). */
    private const val PAD = 5

    /** Space above and below the name's line in a named pill that is coloured. */
    private const val TEXT_V_PAD = 2
    private const val DOT_SIZE = 10

    /** Extra width of the dot's slot over the dot itself (as before the extraction: `DOT_SIZE + 8`). */
    private const val DOT_SLOT_EXTRA = 8

    /** Corner arc diameter of a named pill: a rounded rectangle rather than a full capsule (user request, 2026-11-13). */
    private const val PILL_ARC = 10

    /** How much of the group colour a count pill has (COLL-8, INT-6); the rest is the background it stands on. */
    private const val COUNT_STRENGTH = 0.55

    /** The font of the name, the one the editor tabs use. */
    fun font(): Font = JBUI.CurrentTheme.EditorTabs.font()

    /** The text drawn in the pill: [name] cut at [maxLength] characters with an ellipsis (HDR-3). */
    fun displayName(name: String, maxLength: Int): String {
        val max = maxLength.coerceAtLeast(1)
        return if (name.length > max) name.take(max) + "…" else name
    }

    /** Diameter of the dot of an unnamed group. */
    fun dotSize(): Int = JBUI.scale(DOT_SIZE)

    /** Natural width of the pill: the dot's slot, or the width of [text] (already truncated) plus the padding on both sides. */
    fun naturalWidth(fm: FontMetrics, text: String, isDot: Boolean): Int =
        if (isDot) dotSize() + JBUI.scale(DOT_SLOT_EXTRA) else fm.stringWidth(text) + JBUI.scale(2 * PAD)

    /**
     * Height of a named pill: the name's line height plus a small margin above and below (user request 2026-11-13: a pill that hugs its
     * name, not one as tall as the tab), never more than [maxHeight] (the row's height less its insets).
     */
    fun namedHeight(fm: FontMetrics, maxHeight: Int): Int = minOf(maxHeight, fm.height + 2 * JBUI.scale(TEXT_V_PAD)).coerceAtLeast(1)

    /** The dot of an unnamed group, centred in the area `x, y, w, h`. */
    fun paintDot(g: Graphics2D, color: Color, x: Double, y: Double, w: Double, h: Double) {
        val dot = dotSize().toDouble()
        g.color = color
        g.fill(Ellipse2D.Double(x + (w - dot) / 2, y + (h - dot) / 2, dot, dot))
    }

    /**
     * The named pill filling `x, y, w, h`: HDR-1 fully opaque (the tint opacity, SET-1, is for member tabs only), then the name
     * ([text], already truncated) centred in it in [Contrast.textOn] the fill. Sets the font; the caller's antialiasing hints apply.
     */
    fun paintNamed(g: Graphics2D, color: Color, text: String, x: Double, y: Double, w: Double, h: Double) {
        g.color = color
        val arc = minOf(JBUI.scale(PILL_ARC).toDouble(), h)
        g.fill(RoundRectangle2D.Double(x, y, w, h, arc, arc))
        g.font = font()
        g.color = Color(Contrast.textOn(color.rgb and 0xFFFFFF))
        val fm = g.fontMetrics
        val tx = x + (w - fm.stringWidth(text)) / 2
        val ty = y + (h - fm.height) / 2 + fm.ascent
        g.drawString(text, tx.toFloat(), ty.toFloat())
    }

    /** The fill of a count pill (COLL-8 "+N", the drag chip's tab count): the group colour mixed into [background], lighter than the group's pill. */
    fun countFill(background: Color, groupColor: Color): Color = ColorUtil.mix(background, groupColor, COUNT_STRENGTH)

    /** Width of a count pill showing [text]: never narrower than [height] (a single digit is a rounded square). */
    fun countWidth(fm: FontMetrics, text: String, height: Int): Int = maxOf(naturalWidth(fm, text, false), height)

    /** The count pill filling `x, y, w, h`: [countFill] with [text] in the colour of better contrast on it. */
    fun paintCount(g: Graphics2D, groupColor: Color, background: Color, text: String, x: Double, y: Double, w: Double, h: Double) {
        paintNamed(g, countFill(background, groupColor), text, x, y, w, h)
    }
}
