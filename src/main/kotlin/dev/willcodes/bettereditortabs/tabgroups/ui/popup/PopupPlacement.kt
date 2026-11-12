package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.ui.awt.RelativePoint
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef
import java.awt.Point

/**
 * Where the group popup (§6) opens relative to its header. Only the Top placement exists (Chrome style: directly below the header,
 * aligned to its leading edge); Stage 9 adds the Bottom, Left and Right tab bar positions and flipping here. The returned point is the
 * popup's top-left corner; `JBPopup` keeps the popup on screen.
 */
internal object PopupPlacement {
    /** Top placement: the pill's leading edge (not left of the header component's visible part), just below the header component. */
    fun below(header: HeaderRef): RelativePoint {
        val component = header.component
        val x = maxOf(header.pillBounds.x, component.visibleRect.x)
        return RelativePoint(component, Point(x, component.height))
    }
}
