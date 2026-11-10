package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.util.Weighted
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/**
 * Glass-pane preprocessor that feeds the pointer to [TabDragController] and, once our own tab drag has started, takes the drag and
 * release events away from the platform.
 *
 * Why this pre-empts the platform's `DragHelper`: it is a `MouseDragHelper`, i.e. also a glass-pane preprocessor, with weight 2.
 * `IdeGlassPaneImpl.preprocess` calls the listeners in ascending weight order and stops at the first one that consumed the event
 * (`IdeGlassPaneImpl.kt:370-376,408-412`). This observer has weight 1, so it sees every event first. It never consumes the press
 * (the tab is selected as usual, `DragHelper` records the press) nor any move below the drag threshold; once the pointer has moved
 * past the threshold it consumes every drag event, so `DragHelper.mouseDragged` never runs and cannot start a half drag, and it
 * consumes the release (after telling `DragHelper` about it, see [DragHandOff.resetPlatformHelper]).
 */
internal class DragObserver(private val controller: TabDragController) : MouseAdapter(), Weighted {
    override fun getWeight(): Double = 1.0

    override fun mousePressed(e: MouseEvent) {
        controller.mousePressed(e)
    }

    override fun mouseDragged(e: MouseEvent) {
        if (controller.mouseDragged(e)) e.consume()
    }

    override fun mouseReleased(e: MouseEvent) {
        if (controller.mouseReleased(e)) e.consume()
    }
}
