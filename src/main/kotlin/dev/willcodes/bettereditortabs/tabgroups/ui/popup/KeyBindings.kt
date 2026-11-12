package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

/** Binds [key] (a `KeyStroke` string such as "UP" or "SPACE") to [action] while this component has the focus. */
internal fun JComponent.bindKey(key: String, action: () -> Unit) {
    val name = "bet.popup.$key"
    getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name)
    actionMap.put(name, object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent?) = action()
    })
}

/**
 * Up / Down navigation through the popup's controls in reading order (name field, swatches, action rows), wrapping around. Disabled
 * controls are skipped. Every control is registered with [register], which binds the two keys on it.
 */
internal class PopupFocusRing {
    private val members = mutableListOf<JComponent>()

    fun register(component: JComponent) {
        members += component
        component.bindKey("UP") { move(component, -1) }
        component.bindKey("DOWN") { move(component, 1) }
    }

    private fun move(from: JComponent, delta: Int) {
        val candidates = members.filter { it === from || (it.isFocusable && it.isEnabled && it.isShowing) }
        val index = candidates.indexOf(from)
        if (index < 0 || candidates.size < 2) return
        candidates[Math.floorMod(index + delta, candidates.size)].requestFocusInWindow()
    }
}
