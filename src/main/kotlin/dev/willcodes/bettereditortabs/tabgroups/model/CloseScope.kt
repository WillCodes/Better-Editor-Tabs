package dev.willcodes.bettereditortabs.tabgroups.model

/**
 * The close-scoping rule as a pure function, independent of the IDE.
 *
 * The built-in close actions (Close Others, Close All, Close Unmodified, Close Tabs to the Left/Right, Close All
 * but Pinned) each compute a candidate set. This rule narrows that set by the group of the *context tab*
 * (the right-clicked tab, or the selected tab when the action runs from the main menu or a shortcut):
 *
 * - context tab ungrouped: only ungrouped candidates may be closed (grouped tabs are protected);
 * - context tab in group G: only candidates in G may be closed.
 *
 * Both cases collapse to "candidate group id equals context group id", with `null` meaning ungrouped. Group ids
 * are project-level identities, so a linked group's instances in other splits compare equal.
 */
object CloseScope {
    fun <T> filter(contextGroupId: String?, candidates: List<T>, groupIdOf: (T) -> String?): List<T> =
        candidates.filter { groupIdOf(it) == contextGroupId }
}
