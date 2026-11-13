package dev.willcodes.bettereditortabs.tabgroups.model.strip

/**
 * DRAG-3, a member re-ordered within its own group (user requests 2026-11-13): the trailing slot of the tab's own group works the other
 * way round while the tab has not left the group.
 * - A tab that was a member of the group when the drag began, and has stayed inside it ever since (every landing so far was inside it
 *   or at its trailing slot), is still a member at the group's trailing slot without a hold, so it can be moved to the group's last
 *   position.
 * - Holding it there takes it out, but only with the pointer *beyond* the zone where an outside tab would hold to join
 *   ([holdGroup]; user request 2026-11-13: the two holds must not overlap, or pulling a tab out of a group's end feels like joining).
 *   It then lands outside, untinted (the completed hold, which normally means "join", is ignored until the hold is reset).
 * - Once the tab has landed anywhere outside the group, or left the slot after a hold-out, it is an ordinary tab for that group:
 *   coming back to the trailing slot needs the hold to join.
 *
 * Pure: on every evaluation pass the hold's completed group to [joining] and use the result as the joining group for
 * [DropRules.landing]; then feed the landing to [update].
 */
class OwnGroupStay(startGroup: String?) {
    /** The group the tab still counts as being inside, or null once it has left it (or it was never grouped). */
    var group: String? = startGroup
        private set

    /** The group whose trailing slot the tab was held out at; that completed hold is ignored until the hold is reset or the slot left. */
    private var heldOut: String? = null

    /**
     * The group whose trailing slot the hold timer should run for, or null (no hold): [trailing] is the trailing slot's group of the
     * current landing, [nearEnd] whether the pointer is within the join zone at the group's last tab, [beyondEnd] whether it is
     * clearly past that zone (the join zone plus a little slack, so the two holds never meet).
     */
    fun holdGroup(trailing: String?, nearEnd: Boolean, beyondEnd: Boolean): String? = when {
        trailing == null -> null
        trailing == group -> trailing.takeIf { beyondEnd }
        else -> trailing.takeIf { nearEnd }
    }

    /** The joining group for [DropRules.landing], given the group whose hold has completed ([holdJoining], null if none). */
    fun joining(holdJoining: String?): String? {
        if (holdJoining == null) heldOut = null
        val g = group
        if (g != null) {
            if (holdJoining != g) return g
            // held at its own group's trailing slot: out
            group = null
            heldOut = g
            return null
        }
        return if (holdJoining != null && holdJoining == heldOut) null else holdJoining
    }

    fun update(landing: Landing) {
        val g = group
        if (g != null && landing.groupId != g && landing.trailingGroup != g) group = null
        if (heldOut != null && landing.trailingGroup != heldOut) heldOut = null
    }
}
