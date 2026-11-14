package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.ui.tabs.JBTabsPosition
import com.intellij.ui.tabs.impl.JBTabsImpl
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EditorAreaDrop

/** The side of the editor area this tab component's strip is on ([EditorAreaDrop.StripSide.NONE] while the tabs are hidden). */
internal fun JBTabsImpl.stripSide(): EditorAreaDrop.StripSide {
    if (isHideTabs) return EditorAreaDrop.StripSide.NONE
    return when (tabsPosition) {
        JBTabsPosition.top -> EditorAreaDrop.StripSide.TOP
        JBTabsPosition.bottom -> EditorAreaDrop.StripSide.BOTTOM
        JBTabsPosition.left -> EditorAreaDrop.StripSide.LEFT
        JBTabsPosition.right -> EditorAreaDrop.StripSide.RIGHT
    }
}
