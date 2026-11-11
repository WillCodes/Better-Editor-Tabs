package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.ui.JBColor
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor
import java.awt.Color

/**
 * The §3.3 palette as theme-aware colours: one [JBColor] per [GroupColor], created once. The tab bar hands the same instances back
 * in `groupMovedIn`, so [colorOf] can map a decoration's colour to its [GroupColor] again.
 */
object GroupColors {
    private val byColor: Map<GroupColor, JBColor> = GroupColor.values().associateWith { JBColor(it.light, it.dark) }

    /** The theme-aware colour of [color]; always the same instance. */
    fun jbColor(color: GroupColor): JBColor = byColor.getValue(color)

    /** The palette colour [color] stands for (identity first, then equality); grey for a colour that is not from the palette. */
    fun colorOf(color: Color): GroupColor =
        byColor.entries.firstOrNull { it.value === color }?.key
            ?: byColor.entries.firstOrNull { it.value == color }?.key
            ?: GroupColor.GREY
}
