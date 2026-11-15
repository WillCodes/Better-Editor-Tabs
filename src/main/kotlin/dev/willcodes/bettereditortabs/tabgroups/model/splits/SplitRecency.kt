package dev.willcodes.bettereditortabs.tabgroups.model.splits

/**
 * Pure bookkeeping of "most recently focused split". Generic over the split type so it is testable without the
 * platform; the tab bar feeds it the focus changes it observes.
 */
object SplitRecency {
    /** [history] (most recent first) with [item] moved to the front. */
    fun <T> touch(history: List<T>, item: T): List<T> = if (history.firstOrNull() == item) history else listOf(item) + history.filter { it != item }

    /**
     * The [live] splits, most recently focused first: those in [history] in its order, then the ones never focused in their [live]
     * order (stable). Entries of [history] that are not live drop out.
     */
    fun <T> order(live: List<T>, history: List<T>): List<T> {
        val liveSet = live.toSet()
        val focused = history.filter { it in liveSet }.distinct()
        val focusedSet = focused.toSet()
        return focused + live.filter { it !in focusedSet }
    }
}
