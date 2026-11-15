package dev.willcodes.bettereditortabs.tabgroups.model.strip

/**
 * One element of a strip's order as the plugin keeps it: a tab, or the pill of an *empty* group instance (a group with an
 * enabled auto-group rule whose tabs are all gone, shown as its pill alone, like a collapsed group's). A plain file order cannot say where
 * an empty pill stands, because it has no file; a sequence of items can. Pure, no platform types, unit tested.
 */
sealed class StripItem<out T> {
    data class Tab<T>(val key: T) : StripItem<T>()
    data class EmptyPill(val groupId: String) : StripItem<Nothing>()
}

/** The files of [this] sequence, in order (the empty pills dropped). */
fun <T> List<StripItem<T>>.files(): List<T> = mapNotNull { (it as? StripItem.Tab<T>)?.key }

/** Where the pills of empty group instances stand, derived from a sequence of strip items. */
object EmptyPillSpots {
    /**
     * For every [StripItem.EmptyPill] of [sequence], in sequence order, the key of the last tab in front of it (null: no tab in front,
     * the pill is first). That tab is the pill's anchor: an empty pill stays right behind it, exactly like a collapsed group's pill
     * stays behind the tab that stood in front of the group. Several empty pills in a row share the anchor; their order is the order
     * of the map.
     */
    fun <T> derive(sequence: List<StripItem<T>>): LinkedHashMap<String, T?> {
        val result = LinkedHashMap<String, T?>()
        var last: T? = null
        for (item in sequence) when (item) {
            is StripItem.Tab -> last = item.key
            is StripItem.EmptyPill -> result[item.groupId] = last
        }
        return result
    }

    /**
     * An empty pill never stands inside another group's span. The span of a group is the stretch of [sequence] from
     * its first to its last member tab ([groupOf] says which group a tab belongs to, null = ungrouped). An [StripItem.EmptyPill] strictly
     * inside such a stretch is moved to right behind the span's last tab (several pills keep their order), which is where it stays when a
     * group grows at its end (`[G a b][E] c` with `c` joining G is `[G a b c][E]`). The sequence is returned unchanged (the same list) if
     * no pill stands inside a span.
     */
    fun <T> outsideSpans(sequence: List<StripItem<T>>, groupOf: (T) -> String?): List<StripItem<T>> {
        val first = HashMap<String, Int>()
        val last = HashMap<String, Int>()
        for ((i, item) in sequence.withIndex()) {
            if (item !is StripItem.Tab) continue
            val id = groupOf(item.key) ?: continue
            first.putIfAbsent(id, i)
            last[id] = i
        }
        if (first.isEmpty()) return sequence
        // the span end each misplaced pill goes behind
        val behind = HashMap<Int, MutableList<StripItem<T>>>()
        val moved = HashSet<Int>()
        for ((i, item) in sequence.withIndex()) {
            if (item !is StripItem.EmptyPill) continue
            // the outermost span that contains the pill (groups do not nest, but a damaged order could interleave them)
            val end = first.entries.filter { (id, from) -> from < i && i < last.getValue(id) }.maxOfOrNull { last.getValue(it.key) } ?: continue
            behind.getOrPut(end) { ArrayList() } += item
            moved += i
        }
        if (moved.isEmpty()) return sequence
        val result = ArrayList<StripItem<T>>(sequence.size)
        for ((i, item) in sequence.withIndex()) {
            if (i !in moved) result += item
            behind[i]?.let { result += it }
        }
        return result
    }
}
