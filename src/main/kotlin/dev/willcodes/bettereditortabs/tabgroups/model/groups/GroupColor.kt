package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * The group colour palette (§3.3), in table order. [light] and [dark] are RGB values (0xRRGGBB) for light and dark themes.
 * [messageKey] is the bundle key of the user-visible colour name.
 */
enum class GroupColor(val light: Int, val dark: Int, val messageKey: String) {
    /**
     * COL-3: pushed away from the strip background (darker in light themes, lighter in dark ones), so its tint stays visible on the
     * strip and while a tab is held to join (DRAG-3, DRAG-7).
     */
    GREY(0x70757A, 0xE8EAED, "color.grey"),
    BLUE(0x1A73E8, 0x8AB4F8, "color.blue"),
    RED(0xD93025, 0xF28B82, "color.red"),
    YELLOW(0xF9AB00, 0xFDD663, "color.yellow"),
    GREEN(0x1E8E3E, 0x81C995, "color.green"),
    PINK(0xD01884, 0xFF8BCB, "color.pink"),
    PURPLE(0x9334E6, 0xD7AEFB, "color.purple"),
    CYAN(0x007B83, 0x78D9EC, "color.cyan"),
    ORANGE(0xFA903E, 0xFCAD70, "color.orange");

    /** The RGB value for the given theme. */
    fun rgb(dark: Boolean): Int = if (dark) this.dark else light
}
