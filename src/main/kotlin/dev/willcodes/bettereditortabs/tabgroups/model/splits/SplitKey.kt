package dev.willcodes.bettereditortabs.tabgroups.model.splits

/**
 * A restart-stable address of one editor split. Pure data, no IDE types, so it can be persisted
 * and unit-tested.
 *
 * - [container] is `"main"` for the main editor area, or `"float:<id>"` for a detached editor window,
 *   where `<id>` is the platform's persisted dock-window id (or `?` when it could not be read).
 * - [path] is the route from the container root to the split through the splitter tree, one step per
 *   splitter: `v` (vertical splitter, panes stacked) or `h` (horizontal, side by side), followed by `1` (first
 *   pane: top/left) or `2` (second pane: bottom/right). A container with a single split has the empty path.
 *   Example: `h1v2` = left half, then its bottom half.
 *
 * A key is only a *hint*: it is recomputed from the live component tree whenever it is needed and is never
 * stored on a window as truth. Unsplitting or splitting during a session shifts the paths of other splits,
 * which is why persisted instances are re-attached with [SplitMatcher] (key first, open files as fallback).
 */
data class SplitKey(val container: String, val path: String) {
    fun encode(): String = "$container|$path"

    override fun toString(): String = encode()

    companion object {
        const val MAIN = "main"
        private const val FLOAT_PREFIX = "float:"

        fun floating(id: String?): String = FLOAT_PREFIX + (id ?: "?")

        fun decode(text: String): SplitKey? {
            val bar = text.indexOf('|')
            if (bar <= 0) return null
            return SplitKey(text.substring(0, bar), text.substring(bar + 1))
        }

        /** One splitter step, e.g. `v1`. */
        fun step(vertical: Boolean, first: Boolean): String = (if (vertical) "v" else "h") + (if (first) "1" else "2")
    }
}

/** What the matcher needs to know about a split: its key and the URLs of its open files. */
data class SplitSnapshot(val key: SplitKey, val files: Set<String>)

/**
 * Re-attaches persisted split instances to the splits that exist after a restart, or after the
 * user split/unsplit during a session.
 *
 * Pairs are scored by the overlap of their open files (Jaccard index). An identical [SplitKey] and an identical
 * container only break ties and boost the score; a pair with no common file is never matched, because an
 * instance without any reopened member is dropped anyway. Pairs are assigned greedily, best first,
 * each split and each persisted entry at most once.
 */
object SplitMatcher {
    private const val SAME_KEY_BONUS = 0.5
    private const val SAME_CONTAINER_BONUS = 0.1

    /** Returns persisted-index to current-index. Unmatched persisted entries are absent from the map. */
    fun match(persisted: List<SplitSnapshot>, current: List<SplitSnapshot>): Map<Int, Int> {
        data class Candidate(val p: Int, val c: Int, val score: Double)

        val candidates = ArrayList<Candidate>()
        for ((pi, p) in persisted.withIndex()) {
            for ((ci, c) in current.withIndex()) {
                val common = p.files.count { it in c.files }
                if (common == 0) continue
                val union = p.files.size + c.files.size - common
                var score = common.toDouble() / union
                if (p.key == c.key) score += SAME_KEY_BONUS
                else if (p.key.container == c.key.container) score += SAME_CONTAINER_BONUS
                candidates += Candidate(pi, ci, score)
            }
        }
        // Best score first; ties resolved by position so the result is deterministic.
        candidates.sortWith(compareByDescending<Candidate> { it.score }.thenBy { it.p }.thenBy { it.c })

        val result = LinkedHashMap<Int, Int>()
        val usedCurrent = HashSet<Int>()
        for (cand in candidates) {
            if (cand.p in result || cand.c in usedCurrent) continue
            result[cand.p] = cand.c
            usedCurrent += cand.c
        }
        return result
    }
}
