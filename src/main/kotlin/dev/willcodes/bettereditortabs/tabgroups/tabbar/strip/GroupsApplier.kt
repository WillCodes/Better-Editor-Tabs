package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.impl.JBEditorTabs
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PartialExpand
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import java.awt.Color

/**
 * Which groups the strip has and which of them it shows: the groups as last set, the [shown] view every
 * collaborator reads (a collapsing group counts as expanded while it animates, an empty instance as a collapsed pill), applying a new
 * set of groups (headers, hidden tabs, tint, pill space, the collapse / expand animations) and the "own change" counter that tells the
 * strip's own re-ordering from the user's navigation (selection changes during an own change are not a navigation by the user).
 *
 * Partial expand: a collapsed group with a valid [GroupDecoration.peek] shows that one member beside its header; [shown]
 * normalises the peek (a peek that is not an open member, or of an expanded group, is dropped) and every collaborator reads it from
 * there: the hidden tabs ([CollapseController.sync]), the tint ([applyShown]), the pills' hosting and the count pill. Its animations
 * move only the tabs that change ([PartialExpand.change]): the peek grows out of the pill, the other members grow when the group
 * expands fully, the peek shrinks when the group collapses fully; moving the peek is instant.
 */
internal class GroupsApplier(
    private val tabs: JBEditorTabs,
    private val alive: () -> Boolean,
    private val collapse: CollapseController,
    private val animator: CollapseAnimator,
    private val headers: PillHeaders,
    private val counts: CountPills,
    private val tint: TabTint,
    private val preview: DragPreview,
    private val drag: () -> StripDragSurface,
    private val updateReservations: () -> Boolean,
    private val canAnimate: () -> Boolean,
    /** What runs before new groups are applied (the empty pills' bookkeeping, see [EmptyPills.beforeGroups]). */
    private val beforeApply: (List<GroupDecoration>) -> Unit,
) {
    /** The real groups, as last set. */
    var groups: List<GroupDecoration> = emptyList()
        private set

    /**
     * What the strip shows: [groups], except that a group whose collapse is still animating counts as expanded (its tabs are visible,
     * tinted, the pill is hosted by its first tab). Every collaborator reads this one.
     *
     * An *empty* instance (no member has a tab here; a group with an enabled rule keeps its instance when its last tab goes)
     * is shown as a collapsed group's pill with nothing hidden, whatever its flag says: the header looks the same. It is one
     * strip unit like a collapsed pill; [CollapseController] keeps its place.
     */
    val shown: List<GroupDecoration>
        get() {
            if (groups.isEmpty()) return groups
            val open = openFiles()
            val animating = animator.isActive
            val real = groups.map { g0 ->
                var g = g0
                if (animating && g.collapsed && animator.isCollapsing(g.id)) g = duringCollapse(g, open)
                val peek = PartialExpand.validPeek(g.members, open, g.collapsed, g.peek)
                if (peek == g.peek) g else g.copy(peek = peek)
            }
            if (real.all { g -> g.collapsed || g.members.any { it in open } }) return real
            return real.map { g -> if (!g.collapsed && g.members.none { it in open }) g.copy(collapsed = true) else g }
        }

    /**
     * [g] (collapsed, its collapse animating) as the strip shows it meanwhile: expanded, or, when only its peek shrinks away, still
     * collapsed with that peek.
     */
    private fun duringCollapse(g: GroupDecoration, open: Set<VirtualFile>): GroupDecoration {
        val on = animator.shownDuringCollapse(g.id)
        val peekOnly = on.size == 1 && g.members.count { it in open } > 1
        return if (peekOnly) g.copy(peek = on.first()) else g.copy(collapsed = false, peek = null)
    }

    /** The files that have a tab in this strip, hidden ones included. */
    private fun openFiles(): Set<VirtualFile> = StripReader.openFiles(tabs)

    /** The groups whose pills the strip lays out: [shown] without the group in transit (its tabs stay hidden by [collapse]). */
    val pillGroups: List<GroupDecoration>
        get() = drag().groupInTransit()?.let { id -> shown.filter { it.id != id } } ?: shown

    /**
     * [pillGroups] for the hosting of the pills: an empty instance that a dragged tab would join (a hold behind its pill, previewed by
     * [DragPreview]) counts as an expanded group with that tab as its member, so its pill is hosted by the dragged tab. The strip units
     * ([StripGeometry]) keep seeing the empty pill, so the drop rules judge the same cells before and after the hold completes.
     */
    val layoutGroups: List<GroupDecoration>
        get() {
            val base = pillGroups
            if (!preview.hasMembershipPreview) return base
            return base.map { g -> if (g.collapsed && g.id in collapse.emptyGroups() && preview.joins(g.id)) g.copy(collapsed = false) else g }
        }

    /** Depth of the adapter's own strip changes; selection events during them are not the user's (see the `selectionChanged` listener). */
    private var ownChanges = 0

    /** True while an own change runs. */
    val isOwnChange: Boolean get() = ownChanges > 0

    /**
     * Runs [block] as a change of the strip's own (no selection made during it is the user's navigation, see the `selectionChanged`
     * listener). For multi-step operations driven from outside, such as moving a group to another split (`GroupMove`).
     */
    fun <T> ownChange(block: () -> T): T {
        ownChanges++
        try {
            return block()
        }
        finally {
            ownChanges--
        }
    }

    fun own(block: () -> Unit) = ownChange(block)

    /** [TabBarAdapter.setGroups] without the sort hooks (the sort itself re-orders around collapsed groups with it). */
    fun applyGroups(newGroups: List<GroupDecoration>, animate: Boolean, before: List<GroupDecoration>?) = own {
        beforeApply(newGroups)
        if (animate && canAnimate()) startAnimations(newGroups, before ?: groups) else animator.finishAll()
        groups = newGroups
        applyShown()
        // The tabs of an expanding group were un-hidden by applyShown and must have their first (empty) width before the next paint.
        animator.applySizes()
        tabs.revalidate()
    }

    private fun startAnimations(newGroups: List<GroupDecoration>, before: List<GroupDecoration>) {
        // what a collapse hides: the members of collapsed groups, but not a partial group's peek
        val collapsingFiles = newGroups.filter { it.collapsed }.flatMapTo(HashSet()) { g -> g.members.filter { it != g.peek } }
        val old = before.associateBy { it.id }
        val open = openFiles()
        animator.retain(newGroups.mapTo(HashSet()) { it.id })
        for (g in newGroups) {
            val was = old[g.id] ?: continue
            val running = animator.direction(g.id)
            // A group that had no tab here (an empty instance) and gets its first one is not a collapse or an expand: a
            // collapsed group shows the arrival hidden at once, an expanded one shows it, neither animates (the animation is for flips of
            // groups with open tabs)
            if (running == null && was.members.none { it in open }) continue
            // What is on screen before (where a running animation ends, if one runs) and after; only the tabs that
            // change animate: a collapse shrinks the ones that go, an expand grows the ones that come
            val after = PartialExpand.shown(g.members, open, g.collapsed, g.peek)
            val start = animator.targetOf(g.id) ?: PartialExpand.shown(was.members, open, was.collapsed, was.peek).toSet()
            val change = PartialExpand.change(start, after)
            if (change.none) {
                if (running != null) {
                    val target = animator.targetOf(g.id).orEmpty()
                    animator.updateMembers(g.id, if (running) g.members.filter { it !in target } else g.members.filter { it in after })
                }
                continue
            }
            // the peek moves to another member: neither a collapse nor an expand, instant
            if (change.mixed) {
                animator.end(g.id)
                continue
            }
            if (g.members.none { StripReader.infoOf(tabs, it) != null }) continue
            if (change.shrinking.isNotEmpty()) {
                // The selection changes at the start, the editor never waits for the animation.
                collapse.selectOutside(collapsingFiles)
                animator.begin(g.id, change.shrinking, true, after.toSet())
            }
            else animator.begin(g.id, change.growing, false, after.toSet())
        }
    }

    /** Brings headers, hidden tabs, tint and pill space in line with [shown]. */
    fun applyShown() {
        val now = shown
        headers.sync(now)
        collapse.sync(now)
        val tints = HashMap<VirtualFile, Color>()
        for (g in now) {
            // an expanded group tints its tabs, a partial one its peek: the peek looks like a tab of an expanded group
            val tinted = if (!g.collapsed) g.members else listOfNotNull(g.peek)
            if (tinted.isEmpty()) continue
            val color = tint.colorFor(g.color)
            for (f in tinted) tints[f] = color
        }
        tint.setDesired(tints)
        counts.sync(now, openFiles())
        updateReservations()
        tabs.revalidate()
        tabs.repaint()
    }

    /**
     * Snaps every running collapse / expand animation to its end (a drag must start from a settled strip): the tabs of a group that
     * was collapsing are hidden now.
     */
    fun finishCollapseAnimations() {
        if (!alive() || !animator.isActive) return
        own {
            animator.finishAll()
            applyShown()
        }
    }
}
