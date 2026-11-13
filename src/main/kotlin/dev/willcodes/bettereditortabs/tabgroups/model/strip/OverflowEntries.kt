package dev.willcodes.bettereditortabs.tabgroups.model.strip

/**
 * One tab of a strip as the overflow list (COLL-6) sees it, in the strip's logical order.
 *
 * @param groupId the group the tab belongs to in this split, null if ungrouped.
 * @param inCollapsedGroup the tab is hidden because its group is collapsed: it is always listed.
 * @param hiddenByLayout the layout cuts the (visible) tab off, which is what the platform's own list shows.
 */
internal data class OverflowItem<T>(
    val tab: T,
    val groupId: String?,
    val inCollapsedGroup: Boolean,
    val hiddenByLayout: Boolean,
)

/** One row of the overflow list. */
internal sealed interface OverflowRow<out T> {
    /** The heading of a group's tabs. An empty [name] means "unnamed": the list shows a generic title (HDR-4). */
    data class Heading(val groupId: String, val name: String) : OverflowRow<Nothing> {
        val isUnnamed: Boolean get() = name.isBlank()
    }

    /** A listed tab. */
    data class Tab<T>(val item: OverflowItem<T>) : OverflowRow<T>
}

/**
 * The rows of the overflow list of a single-row strip (COLL-6): every tab the layout hides plus all tabs of collapsed groups, in the
 * strip's logical order. Each run of a group's tabs starts with a heading naming the group. There are no separator lines: the list
 * shows a group's heading and tabs on the group's tint, so the background alone tells the groups apart (user request, Stage 8 fix
 * round 1). Pure.
 */
internal object OverflowEntries {

    /** [items] in logical order; [names] maps a group id to its name (missing = unnamed). */
    fun <T> build(items: List<OverflowItem<T>>, names: Map<String, String>): List<OverflowRow<T>> {
        val rows = ArrayList<OverflowRow<T>>()
        var previousGroup: String? = null
        for (item in items) {
            if (!item.inCollapsedGroup && !item.hiddenByLayout) continue
            val group = item.groupId
            if (group != null && group != previousGroup) rows += OverflowRow.Heading(group, names[group].orEmpty())
            rows += OverflowRow.Tab(item)
            previousGroup = group
        }
        return rows
    }
}
