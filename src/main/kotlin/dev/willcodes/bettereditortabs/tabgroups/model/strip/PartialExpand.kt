package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Rectangle

/**
 * Pure rules of the partial expand (COLL-8): a collapsed group that shows one member beside its header (the *peek*) and the count of
 * the members still hidden ("+N"). No platform types, unit tested.
 */
object PartialExpand {
    /**
     * The peek that counts: [peek] if the group is collapsed and it is one of its [members] that has a tab in the split ([open]); null
     * otherwise (an expanded group, a file that is not a member, a closed tab: the group is then collapsed fully).
     */
    fun <T : Any> validPeek(members: List<T>, open: Set<T>, collapsed: Boolean, peek: T?): T? =
        peek?.takeIf { collapsed && it in open && it in members }

    /** The members of a group that stand in the strip: every open one when expanded, the valid peek alone when partial, none when collapsed. */
    fun <T : Any> shown(members: List<T>, open: Set<T>, collapsed: Boolean, peek: T?): List<T> {
        if (!collapsed) return members.filter { it in open }
        return listOfNotNull(validPeek(members, open, true, peek))
    }

    /** N of "+N": the open members that stay hidden beside the valid peek; 0 without a peek (a fully collapsed group has no count). */
    fun <T : Any> hiddenCount(members: List<T>, open: Set<T>, collapsed: Boolean, peek: T?): Int {
        val p = validPeek(members, open, collapsed, peek) ?: return 0
        return members.count { it in open && it != p }
    }

    /** What changes between two sets of shown members: the files that shrink away and the files that grow in. */
    data class Change<T>(val shrinking: List<T>, val growing: List<T>) {
        /** Nothing changes. */
        val none: Boolean get() = shrinking.isEmpty() && growing.isEmpty()

        /** Files leave and others arrive (the peek moves): not a collapse or an expand, it is instant (COLL-7). */
        val mixed: Boolean get() = shrinking.isNotEmpty() && growing.isNotEmpty()
    }

    /** [before] to [after], both as sets of shown members. */
    fun <T> change(before: Collection<T>, after: Collection<T>): Change<T> {
        val b = before.toHashSet()
        val a = after.toHashSet()
        return Change(before.filter { it !in a }, after.filter { it !in b })
    }

    /**
     * The one rectangle a partly shown group covers in the drop rules (DRAG-6, COLL-8): the union of the non-empty [parts] (header,
     * peek tab, count), so there is no slot between them. Empty if every part is scrolled out.
     */
    fun unitRect(parts: List<Rectangle>): Rectangle {
        val shown = parts.filter { !it.isEmpty }
        if (shown.isEmpty()) return Rectangle()
        val r = Rectangle(shown.first())
        for (p in shown.drop(1)) r.add(p)
        return r
    }
}
