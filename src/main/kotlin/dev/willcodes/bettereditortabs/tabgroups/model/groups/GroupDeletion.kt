package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * Where a Delete Group request came from. Each source has its own "confirm before deleting" setting (LIFE-3b): [MENU] is SET-4 (the
 * group popup, and the Delete Current Group action of Stage 12), [MIDDLE_CLICK] is SET-5 (INT-3).
 */
enum class DeleteTrigger { MENU, MIDDLE_CLICK }

/** What a Delete Group request does first (LIFE-3). Stage 7 adds the linked-instances dialog (LIFE-3a) as a further step. */
enum class DeleteStep {
    /** LIFE-3c, or the confirmation is switched off (SET-4, SET-5): delete without asking. */
    DELETE_NOW,

    /** LIFE-3b: ask "Delete ‹name› and close its N tabs?" first. */
    CONFIRM,
}

/**
 * LIFE-3b / LIFE-3c: an instance without open tabs is deleted immediately; otherwise the confirmation applies unless its setting
 * ([confirmEnabled]: SET-4 for the menu, SET-5 for middle-click) is off.
 */
fun deleteStep(openTabCount: Int, confirmEnabled: Boolean): DeleteStep =
    if (openTabCount == 0 || !confirmEnabled) DeleteStep.DELETE_NOW else DeleteStep.CONFIRM
