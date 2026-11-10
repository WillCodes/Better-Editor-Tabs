package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBTabsImpl
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.event.MouseEvent

/**
 * Hands a tab drag over to the platform's drag-out when the pointer leaves the tab strip, so that dropping on another split's
 * strip, on a split edge, or outside the window (new window) works exactly as it does without us.
 *
 * Internal APIs wrapped, and why: `TabInfo.dragOutDelegate` is what the platform's `DragHelper` calls when a drag leaves the
 * strip. For editor tabs it is `EditorTabbedContainerDragOutDelegate` (`EditorTabbedContainer.kt`), which on `dragOutStarted` hides
 * the source tab and starts a `DockManager` drag session (drag image, drop target highlighting), on `dragOutFinished` closes the
 * source tab (unless Ctrl is held) and lets the session drop it, and on `dragOutCancelled` shows the tab again. The delegate is
 * public API on `TabInfo`, so the hand-over is just the same four calls `DragHelper` would have made, with the same events.
 *
 * `DragHelper` itself never saw the gesture (its drag events are consumed by [DragObserver]), so none of its drag state has to be
 * undone; only the press it recorded is cleared by [resetPlatformHelper].
 */
internal class DragHandOff(private val info: TabInfo) {
    private val delegate: TabInfo.DragOutDelegate? = info.dragOutDelegate

    /** False if the tab cannot be dragged out (no delegate); nothing was started then. */
    val available: Boolean get() = delegate != null

    fun start(event: MouseEvent) {
        val d = delegate ?: return
        d.dragOutStarted(event, info)
        d.processDragOut(event, info)
    }

    fun process(event: MouseEvent) {
        delegate?.processDragOut(event, info)
    }

    fun finish(event: MouseEvent) {
        delegate?.dragOutFinished(event, info)
    }

    fun cancel() {
        delegate?.dragOutCancelled(info)
    }

    companion object {
        /**
         * `MouseDragHelper.mousePressed` is final and records the press point of every gesture that starts on a tab label; only its
         * own `mouseReleased` forgets it again. Our drag consumes the release, so `DragHelper` is told about it here (with no
         * drag in progress, it just forgets the press and consumes nothing). Without this a stale press could start a platform
         * drag from a later drag event. See [PlatformInternals.resetDragHelper].
         */
        fun resetPlatformHelper(tabs: JBTabsImpl, release: MouseEvent) {
            PlatformInternals.resetDragHelper(tabs, release)
        }
    }
}
