package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.MouseDragHelper
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.tabs.impl.JBEditorTabs
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * The gesture of a drag of a whole group by its header (INT-6, SPLIT-5); what happens during the drag is [GroupDrag].
 *
 * Wraps `MouseDragHelper` (public API): it listens on the glass pane, so the drag continues when the pointer leaves the header,
 * and gives the click-versus-drag dead zone, Esc to cancel and the Alt-only-DnD setting for free. It never starts on a tab
 * label (that is the plugin's own tab drag, [TabDragController]; the two never fight because a press on a pill is not a press on a
 * label). Drag-out mode of `MouseDragHelper` is not used: leaving the strip is just another kind of target in [GroupDrag].
 */
internal class HeaderDragHelper(private val dnd: TabBarDnd, parent: Disposable) : MouseDragHelper<JBEditorTabs>(parent, dnd.tabs) {
    private var pressedGroup: String? = null
    private var drag: GroupDrag? = null

    init {
        Disposer.register(parent) {
            drag?.dispose()
            drag = null
        }
    }

    override fun canStartDragging(dragComponent: JComponent, dragComponentPoint: Point): Boolean =
        dnd.headerAt(dragComponentPoint) != null

    override fun processMousePressed(event: MouseEvent) {
        pressedGroup = dnd.headerAt(RelativePoint(event).getPoint(dnd.tabs))?.groupId
    }

    override fun processDrag(event: MouseEvent, dragToScreenPoint: Point, startScreenPoint: Point) {
        if (isDragJustStarted) {
            val group = pressedGroup ?: return
            val started = GroupDrag(dnd, group)
            if (!started.begin()) return
            drag = started
        }
        val running = drag ?: return
        running.move(dragToScreenPoint)
        event.consume()
    }

    override fun processDragFinish(event: MouseEvent, willDragOutStart: Boolean) {
        val finished = drag ?: return
        drag = null
        finished.finish()
    }

    override fun processDragCancel() {
        val cancelled = drag ?: return
        drag = null
        cancelled.cancel()
    }
}
