package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * Where a Delete Group request came from. Each source has its own "confirm before deleting" setting: [MENU] is the
 * group popup and the Delete Current Group action, [MIDDLE_CLICK] is a middle-click on a group's pill.
 */
enum class DeleteTrigger { MENU, MIDDLE_CLICK }

/** What a Delete Group request does first. */
enum class DeleteStep {
    /** The instance has no open tabs, or the confirmation is switched off: delete without asking. */
    DELETE_NOW,

    /** Ask "Delete ‹name› and close its N tabs?" first. */
    CONFIRM,

    /** The group has instances in other splits and open tabs here: ask Move / Close / Cancel. Always asked. */
    LINKED_CHOICE,
}

/**
 * An instance without open tabs is deleted immediately, linked or not; with open tabs and instances in other splits
 * ([hasLinkedInstances]) the Move / Close / Cancel choice is always asked, whatever the settings (it is a choice, not a
 * confirmation); otherwise the confirmation applies unless its setting ([confirmEnabled], one setting per source)
 * is off.
 */
fun deleteStep(openTabCount: Int, confirmEnabled: Boolean, hasLinkedInstances: Boolean = false): DeleteStep = when {
    openTabCount == 0 -> DeleteStep.DELETE_NOW
    hasLinkedInstances -> DeleteStep.LINKED_CHOICE
    !confirmEnabled -> DeleteStep.DELETE_NOW
    else -> DeleteStep.CONFIRM
}

/**
 * For "Move": the split that receives the tabs: the first of [recent] (most recently focused first) that has an instance of the
 * group ([splitsWithGroup]) and is not [current]. A split with the group that is missing from [recent] comes after the listed ones, in
 * the order of [splitsWithGroup]. Null if no other split has the group.
 */
fun <S> linkedMoveTarget(recent: List<S>, splitsWithGroup: Collection<S>, current: S): S? {
    val others = splitsWithGroup.filter { it != current }
    return recent.firstOrNull { it in others } ?: others.firstOrNull()
}
