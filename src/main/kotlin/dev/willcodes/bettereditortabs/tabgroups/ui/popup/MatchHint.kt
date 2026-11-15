package dev.willcodes.bettereditortabs.tabgroups.ui.popup

/**
 * The match hint under the regex field of the group popup: the first [LIMIT] open tabs the rule would
 * move, by file name, each cut to [NAME_LIMIT] characters with "..." for the rest, the part of the name the pattern matched in bold,
 * then "..." if there are more. Pure text work (HTML for a wrapping label); the label wraps between the names at the popup's width.
 */
internal object MatchHint {
    const val LIMIT = 5
    const val NAME_LIMIT = 20
    private const val ELLIPSIS = "..."

    /** One matching tab: its file [name] and its [path] as the rule saw it (project-relative, or absolute outside the project). */
    data class Entry(val name: String, val path: String)

    /**
     * The hint's HTML body (without `<html>`): [prefix], then the first [LIMIT] of [entries] (in the order given, duplicates dropped)
     * separated by ", ", then ", ..." if there are more. [regex] marks the bold part: its first match in the path, where it overlaps the
     * file name. Null for no entries.
     */
    fun html(prefix: String, entries: List<Entry>, regex: Regex): String? {
        val unique = entries.distinct()
        if (unique.isEmpty()) return null
        val shown = unique.take(LIMIT).map { name(it, regex) }
        val more = if (unique.size > LIMIT) ", $ELLIPSIS" else ""
        return escape(prefix) + " " + shown.joinToString(", ") + more
    }

    /** One name: cut to [NAME_LIMIT] characters, the matched part bold, never broken inside the name. */
    fun name(entry: Entry, regex: Regex): String {
        val full = entry.name
        val cut = full.length > NAME_LIMIT
        val visible = if (cut) full.substring(0, NAME_LIMIT - ELLIPSIS.length) else full
        // where the first match lies in the name (the name is the end of the path)
        val nameStart = entry.path.length - full.length
        val match = regex.find(entry.path)?.range
        var boldFrom = -1
        var boldTo = -1
        if (nameStart >= 0 && entry.path.endsWith(full) && match != null && !match.isEmpty()) {
            boldFrom = maxOf(match.first - nameStart, 0)
            boldTo = minOf(match.last + 1 - nameStart, visible.length)
        }
        val sb = StringBuilder("<nobr>")
        if (boldFrom in 0 until boldTo) {
            sb.append(escape(visible.substring(0, boldFrom)))
            sb.append("<b>").append(escape(visible.substring(boldFrom, boldTo))).append("</b>")
            sb.append(escape(visible.substring(boldTo)))
        } else {
            sb.append(escape(visible))
        }
        if (cut) sb.append(ELLIPSIS)
        return sb.append("</nobr>").toString()
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
