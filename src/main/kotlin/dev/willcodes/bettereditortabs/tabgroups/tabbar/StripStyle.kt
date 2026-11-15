package dev.willcodes.bettereditortabs.tabgroups.tabbar

/**
 * How the group headers and tinted tabs look, from the settings. Set through [TabBar.style]; the tab bar applies it to every split.
 *
 * @param opacity how much of the group colour shows in the tint of member tabs, 0.0 to 1.0. Headers ignore it: pills
 *   and dots are always fully opaque. Values outside the range are clamped where they are used.
 * @param maxNameLength longest group name shown in a header; a longer name is cut and ends with "…", the full name is the header's
 *   tooltip. Values below 1 are treated as 1.
 * @param animateCollapse whether collapsing and expanding a group is animated. Off: every toggle is instant.
 */
data class StripStyle(val opacity: Double, val maxNameLength: Int, val animateCollapse: Boolean = true) {
    companion object {
        /** The look the plugin ships with: 25% colour, names up to 20 characters, animated collapse. */
        val DEFAULT = StripStyle(opacity = 0.25, maxNameLength = 20, animateCollapse = true)
    }
}
