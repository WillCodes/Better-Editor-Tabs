package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * HDR-2: the text colour of a group name on its opaque pill (HDR-1). Of the two candidates, [DARK_TEXT] and [LIGHT_TEXT], the one with
 * the higher WCAG 2 contrast ratio against the fill wins. The fill is the group colour as the current theme resolves it, so the choice is
 * made per palette colour and theme. Pure; colours are RGB ints (0xRRGGBB, alpha ignored).
 */
object Contrast {
    /** Near-black name text (Chrome's dark text colour). */
    const val DARK_TEXT = 0x202124

    /** White name text. */
    const val LIGHT_TEXT = 0xFFFFFF

    /** The text colour for a name drawn on [fill]: [DARK_TEXT] or [LIGHT_TEXT], whichever contrasts more (dark on a tie). */
    fun textOn(fill: Int): Int =
        if (ratio(DARK_TEXT, fill) >= ratio(LIGHT_TEXT, fill)) DARK_TEXT else LIGHT_TEXT

    /** WCAG 2 contrast ratio of two colours, from 1.0 (same luminance) to 21.0 (black on white). Symmetric. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** WCAG 2 relative luminance of an sRGB colour, 0.0 (black) to 1.0 (white). */
    fun luminance(rgb: Int): Double =
        0.2126 * linear((rgb shr 16) and 0xFF) + 0.7152 * linear((rgb shr 8) and 0xFF) + 0.0722 * linear(rgb and 0xFF)

    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }
}
