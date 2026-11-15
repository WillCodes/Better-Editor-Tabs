package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Point

/** Tunables of hold-to-join. */
object DragHoldDefaults {
    /** How long the pointer must stay in a group's trailing slot before releasing adds the tab to the group. */
    const val HOLD_MILLIS = 400

    /** Pointer movement (unscaled px) that still counts as holding still. */
    const val JITTER_PX = 4
}

/**
 * Pure state machine behind hold-to-join: no clock, no Swing, unit tested.
 *
 * Feed it the group whose trailing slot the dragged tab currently occupies (`null` if none) together with the pointer position on
 * every drag event; start a timer when it answers [Action.ARM], cancel it on [Action.DISARM], and call [fire] when the timer runs
 * out. Rules:
 * - entering a slot arms the hold; leaving it (or entering another group's slot) cancels it and forgets that the tab was joining;
 * - while armed, moving the pointer by more than [jitter] px re-arms it (moving is not holding);
 * - once the hold has completed ([joiningGroup] set) it stays until the slot is left, so a small shake can't lose it.
 */
class DragHoldState(private val jitter: Int) {
    enum class Action { NONE, ARM, DISARM }

    /** The group whose trailing slot the tab is in, or null. */
    var slotGroup: String? = null
        private set

    /** The group the tab will join if released now (the hold has completed), or null. */
    var joiningGroup: String? = null
        private set

    private var armed = false
    private var anchor = Point()

    fun update(slot: String?, x: Int, y: Int): Action {
        if (slot != slotGroup) {
            slotGroup = slot
            joiningGroup = null
            if (slot == null) {
                val wasArmed = armed
                armed = false
                return if (wasArmed) Action.DISARM else Action.NONE
            }
            anchor = Point(x, y)
            armed = true
            return Action.ARM
        }
        if (slot == null || joiningGroup != null) return Action.NONE
        if (anchor.distance(x.toDouble(), y.toDouble()) > jitter) {
            anchor = Point(x, y)
            armed = true
            return Action.ARM
        }
        return Action.NONE
    }

    /** The timer ran out. Returns true if this completed a hold. */
    fun fire(): Boolean {
        val slot = slotGroup
        if (!armed || slot == null || joiningGroup != null) return false
        armed = false
        joiningGroup = slot
        return true
    }

    fun reset() {
        slotGroup = null
        joiningGroup = null
        armed = false
    }
}
