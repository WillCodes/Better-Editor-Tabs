package dev.willcodes.bettereditortabs.tabgroups.tabbar

/**
 * How the group headers and tinted tabs look, from the settings (SET-1, SET-2). Set through [TabBar.style]; the tab bar applies it to every split.
 *
 * @param opacity how much of the group colour shows: the fill of the header pill (HDR-1) and the tint of member tabs (LINE-1), 0.0 to 1.0.
 *   Values outside the range are clamped where they are used.
 * @param maxNameLength longest group name shown in a header; a longer name is cut and ends with "…", the full name is the header's
 *   tooltip (HDR-3). Values below 1 are treated as 1.
 */
data class StripStyle(val opacity: Double, val maxNameLength: Int) {
    companion object {
        /** The look the plugin ships with: 25% colour, names up to 20 characters. */
        val DEFAULT = StripStyle(opacity = 0.25, maxNameLength = 20)
    }
}
