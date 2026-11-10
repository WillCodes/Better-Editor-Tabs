package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.tabgroups.model.strip.DragHoldDefaults
import dev.willcodes.bettereditortabs.tabgroups.model.strip.DragHoldState
import java.awt.Point
import javax.swing.Timer

/** [DragHoldState] driven by a Swing timer. [onJoining] runs on the EDT when a hold completes. */
internal class DragHold(private val onJoining: () -> Unit) {
    private val state = DragHoldState(JBUI.scale(DragHoldDefaults.JITTER_PX))
    private val timer = Timer(DragHoldDefaults.HOLD_MILLIS) {
        if (state.fire()) onJoining()
    }.apply { isRepeats = false }

    val joiningGroup: String? get() = state.joiningGroup

    fun update(slot: String?, pointerScreen: Point) {
        when (state.update(slot, pointerScreen.x, pointerScreen.y)) {
            DragHoldState.Action.ARM -> timer.restart()
            DragHoldState.Action.DISARM -> timer.stop()
            DragHoldState.Action.NONE -> Unit
        }
    }

    fun reset() {
        timer.stop()
        state.reset()
    }
}
