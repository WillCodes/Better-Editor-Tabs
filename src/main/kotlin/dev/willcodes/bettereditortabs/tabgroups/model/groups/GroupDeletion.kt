package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * Where a Delete Group request came from. Each source has its own "confirm before deleting" setting (LIFE-3b): [MENU] is SET-4 (the
 * group popup, and the Delete Current Group action of Stage 12), [MIDDLE_CLICK] is SET-5 (INT-3).
 */
enum class DeleteTrigger { MENU, MIDDLE_CLICK }

/** What a Delete Group request does first (LIFE-3). */
enum class DeleteStep {
    /** LIFE-3c, or the confirmation is switched off (SET-4, SET-5): delete without asking. */
    DELETE_NOW,

    /** LIFE-3b: ask "Delete ‹name› and close its N tabs?" first. */
    CONFIRM,

    /** LIFE-3a: the group has instances in other splits and open tabs here: ask Move / Close / Cancel. Always asked. */
    LINKED_CHOICE,
}

/**
 * LIFE-3: an instance without open tabs is deleted immediately, linked or not (LIFE-3c); with open tabs and instances in other splits
 * ([hasLinkedInstances]) the Move / Close / Cancel choice is always asked, whatever the settings (LIFE-3a: it is a choice, not a
 * confirmation); otherwise the confirmation applies unless its setting ([confirmEnabled]: SET-4 for the menu, SET-5 for middle-click)
 * is off (LIFE-3b).
 */
fun deleteStep(openTabCount: Int, confirmEnabled: Boolean, hasLinkedInstances: Boolean = false): DeleteStep = when {
    openTabCount == 0 -> DeleteStep.DELETE_NOW
    hasLinkedInstances -> DeleteStep.LINKED_CHOICE
    !confirmEnabled -> DeleteStep.DELETE_NOW
    else -> DeleteStep.CONFIRM
}

/**
 * LIFE-3a "Move": the split that receives the tabs: the first of [recent] (most recently focused first) that has an instance of the
 * group ([splitsWithGroup]) and is not [current]. A split with the group that is missing from [recent] comes after the listed ones, in
 * the order of [splitsWithGroup]. Null if no other split has the group.
 */
fun <S> linkedMoveTarget(recent: List<S>, splitsWithGroup: Collection<S>, current: S): S? {
    val others = splitsWithGroup.filter { it != current }
    return recent.firstOrNull { it in others } ?: others.firstOrNull()
}
