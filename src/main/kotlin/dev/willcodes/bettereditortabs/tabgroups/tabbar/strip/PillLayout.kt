package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.ui.tabs.impl.TabLabel
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PillHosting
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import java.util.IdentityHashMap

/**
 * Where a pill is hosted: [label] null means "no tab at all" (every tab is collapsed); the pill then sits in the empty strip.
 * [leading] is the side of the label where the space is reserved, [length] the reserved length along the strip.
 */
internal class PillAssignment(val header: GroupHeader, val label: TabLabel?, val leading: Boolean, val length: Int)

/**
 * Where the space for a group or tab that is being dragged in from another split opens: before a tab, before a collapsed group's pill,
 * or at the end. [BeforeTab.afterPills]: behind the pills hosted on that tab's leading side (between a group's pill and its first tab,
 * or between a collapsed group's pill and the tab behind it); otherwise in front of them.
 */
internal sealed class ForeignAnchor {
    data class BeforeTab(val file: VirtualFile, val afterPills: Boolean = false) : ForeignAnchor()
    data class BeforeCollapsedGroup(val groupId: String) : ForeignAnchor()
    object AtEnd : ForeignAnchor()
}

/**
 * The space [length] (along the strip) that a group or tab dragged in from another split takes in this strip, and where. [pinned]: it is
 * a pinned tab, which belongs to the pinned row when pinned tabs have a row of their own; everything else (an unpinned tab, a group) is
 * an unpinned item and belongs to the rows after it.
 */
internal class ForeignGap(val anchor: ForeignAnchor, val length: Int, val pinned: Boolean = false)

/** One element reserved next to a label (or in the empty strip): a pill (`header` set) or the foreign gap's space. */
internal class SpaceItem(val header: GroupHeader?, val length: Int)

/** The foreign gap resolved against the current hosting: [label] null = in the empty strip. [before] null = at the front, unless [atEnd]. */
internal class Spacer(
    val label: TabLabel?,
    val leading: Boolean,
    val before: PillAssignment?,
    val atEnd: Boolean,
    val length: Int,
    val afterPills: Boolean = false,
)

/**
 * Pill hosting: decides which tab label hosts each group's pill and reserves the pill's space there ([TabLabelSpace]).
 *
 * Owns: the current [assignments], the reserved space, the strip order of the last pass ([lastOrder]) and the snapshot of the tabs
 * in front of each collapsed group (so a platform drag cannot carry a pill along). Internal APIs wrapped: `JBTabsImpl.getVisibleInfos`
 * / `getTabLabel` (via [TabLabelSpace], which wraps `TabLabel.border`). The pure rule is [PillHosting].
 *
 * Derived from the current strip order, the group model, the drag preview and the platform drag state only, so [update] is idempotent.
 * EDT only.
 */
internal class PillLayout(
    private val tabs: JBEditorTabs,
    private val alive: () -> Boolean,
    private val groups: () -> List<GroupDecoration>,
    private val headerOf: (String) -> GroupHeader?,
    private val preview: DragPreview,
    private val collapse: CollapseController,
    private val platformDraggedFile: () -> VirtualFile?,
    /** The tab that is out of the strip because its drag is over another split ([TabTransit]), or null. */
    private val inTransit: () -> TabTransit.State? = { null },
) {
    val space = TabLabelSpace(tabs)

    /** Where each pill is hosted, as of the last [update]. */
    var assignments: List<PillAssignment> = emptyList()
        private set

    /** The space for a group dragged in from another split (see [ForeignGap]); set by [StripDragSurface.setForeignGap]. */
    var foreign: ForeignGap? = null

    /** [foreign] resolved against the hosting of the last [update]. */
    var spacer: Spacer? = null
        private set

    /** Visible files in strip order at the last [update]; used to re-anchor a collapsed group when its anchor tab closes. */
    var lastOrder: List<VirtualFile> = emptyList()
        private set

    /** The tabs that stood in front of each collapsed group when no platform drag was active (what the platform drag must not move). */
    private val collapsedBefore = HashMap<String, Set<VirtualFile>>()

    /**
     * Decides which tab label hosts each pill and reserves the pill's space there. Returns true if a label border changed, i.e. the
     * platform has to lay the strip out again.
     *
     * - Expanded group: the first member that the platform is not dragging, leading side.
     * - Collapsed group (no visible member): the first tab after the tabs that stood in front of the group (the anchor and
     *   everything before it, see [PillHosting.hostFor]), leading side; when the group sat at the end of the strip, the last
     *   tab, trailing side. Hosting it on a neighbour keeps its place in the strip without an own component in the layout.
     * - While the platform drags a tab, "tabs in front of the group" is the snapshot taken before the drag, so neither a dragged
     *   first member nor a dragged anchor carries the pill along.
     */
    fun update(): Boolean {
        if (!alive()) return false
        val groups = groups()
        val infos = tabs.getVisibleInfos()
        val files = infos.map { it.`object` as? VirtualFile }
        // Pinned tabs in a row of their own: a pill never sits in the pinned row (no group is ever pinned). Pinned tabs cannot host;
        // with no unpinned tab to host it, a collapsed group's pill is loose and gets the row below the pinned row ([SpareRow]).
        val pinnedApart = SpareRow.pinnedRowApart(tabs)
        val hostFiles = if (pinnedApart) files.mapIndexed { i, f -> f.takeUnless { infos[i].isPinned } } else files
        val dragged = platformDraggedFile()
        val dragging = dragged != null
        val transit = inTransit()
        val list = ArrayList<PillAssignment>()
        // Pills hosted by their own group's first tab. A collapsed group's pill can share that host label (it stands in front of the
        // group), so these must come last among the label's leading items, right before their tab, whatever the groups' list order.
        val ownHost = HashSet<GroupHeader>()
        if (!tabs.isHideTabs) {
            for (g in groups) {
                val header = headerOf(g.id) ?: continue
                val members = preview.effectiveMembers(g)
                val hasVisibleMember = files.any { it != null && it in members }
                // every visible member is being dragged away by the plugin's drag: the group is parked, its pill stays behind
                // (also a group whose only tab is out of the strip because its drag is over another split: DRAG-8, the pill stays)
                val transitParked = transit != null && !g.collapsed && !hasVisibleMember && transit.file in g.members
                val parked = transitParked || (!g.collapsed && !hasVisibleMember && files.any { it != null && it in g.members })
                val before: Set<VirtualFile> = if (g.collapsed && !hasVisibleMember) {
                    val snapshot = if (dragging) collapsedBefore[g.id] else null
                    snapshot ?: PillHosting.tabsBefore(files, collapse.anchorOf(g.id)).also { if (!dragging) collapsedBefore[g.id] = it }
                }
                else if (transitParked) transit!!.tabsBefore[g.id].orEmpty()
                else if (parked) preview.tabsBefore(g.id) ?: files.subList(0, files.indexOfFirst { it != null && it in g.members }).filterNotNull().toSet()
                else emptySet()
                val host = PillHosting.hostFor(hostFiles, if (parked) preview.draggedFile else dragged, { it in members }, before)
                    ?.takeUnless { pinnedApart && infos[it.index].isPinned }
                val label = host?.let { tabs.getTabLabel(infos[it.index]) }
                if (host != null && label != null && (hasVisibleMember || g.collapsed || parked)) {
                    list += PillAssignment(header, label, host.leading, header.pillLength())
                    if (hasVisibleMember && !parked) ownHost += header
                }
                else if (g.collapsed && (infos.isEmpty() || pinnedApart) && collapse.hasAnchor(g.id)) {
                    list += PillAssignment(header, null, true, header.pillLength())
                }
            }
        }
        if (!dragging) collapsedBefore.keys.retainAll(groups.filter { it.collapsed }.map { it.id }.toSet())
        assignments = orderSharedPills(list, ownHost, groups)
        lastOrder = files.filterNotNull()
        spacer = resolveSpacer(infos, list)

        // Top / Bottom: the space is a border inset of the host label. Left / Right: the pills are rows of their own, the labels are
        // moved after the layout ([VerticalRows]) and nothing is reserved in a label.
        val horizontal = tabs.isHorizontalTabs
        var changed = false
        val keep = ArrayList<TabLabel>()
        for (info in infos) {
            val label = tabs.getTabLabel(info) ?: continue
            keep += label
            val (lead, trail) = itemsOf(label)
            val trailing = if (horizontal) PillHosting.trailingReserve(trail.map { it.length }, tabs.tabHGap) else 0
            changed = space.set(label, if (horizontal) lead.sumOf { it.length } else 0, trailing) || changed
        }
        return space.releaseExcept(keep) || changed
    }

    /** One side of one host label (or the empty strip, [label] null); identity of the label, not equality. */
    private class Slot(val label: TabLabel?, val leading: Boolean) {
        override fun equals(other: Any?) = other is Slot && other.label === label && other.leading == leading
        override fun hashCode() = System.identityHashCode(label) * 31 + leading.hashCode()
    }

    /**
     * Orders the pills that share a host label side ([PillHosting.orderShared]) from where each group stood, not from the groups list:
     * a collapsed group that stood behind another collapsed group's tab follows that group, collapsed groups at the same place keep
     * the order they collapsed in, and an expanded group's own pill ([ownHost]) is last, right before its tab. Every consumer reads the
     * order per label side, so the buckets are simply concatenated.
     */
    private fun orderSharedPills(list: List<PillAssignment>, ownHost: Set<GroupHeader>, groups: List<GroupDecoration>): List<PillAssignment> {
        if (list.size < 2) return list
        val buckets = LinkedHashMap<Slot, MutableList<PillAssignment>>()
        for (a in list) buckets.getOrPut(Slot(a.label, a.leading)) { ArrayList() } += a
        val membersOf = groups.associate { it.id to it.members }
        val result = ArrayList<PillAssignment>(list.size)
        for (bucket in buckets.values) {
            val byId = bucket.associateBy { it.header.groupId }
            result += PillHosting.orderShared(
                bucket,
                parentOf = { a -> collapse.anchorParent(a.header.groupId)?.let { (owner, _) -> byId[owner] } },
                siblingRank = { a ->
                    val parent = collapse.anchorParent(a.header.groupId)
                    if (parent != null && parent.first in byId) membersOf[parent.first].orEmpty().indexOf(parent.second).toLong()
                    else collapse.collapseOrder(a.header.groupId)
                },
                isOwn = { it.header in ownHost },
            )
        }
        return result
    }

    private fun resolveSpacer(infos: List<com.intellij.ui.tabs.TabInfo>, list: List<PillAssignment>): Spacer? {
        val f = foreign ?: return null
        if (tabs.isHideTabs) return null
        return when (val a = f.anchor) {
            is ForeignAnchor.BeforeTab -> {
                val label = infos.firstOrNull { it.`object` == a.file }?.let { tabs.getTabLabel(it) } ?: return null
                Spacer(label, true, null, false, f.length, a.afterPills)
            }
            is ForeignAnchor.BeforeCollapsedGroup -> {
                val pill = list.firstOrNull { it.header.groupId == a.groupId } ?: return null
                Spacer(pill.label, pill.leading, pill, false, f.length)
            }
            ForeignAnchor.AtEnd -> {
                val last = infos.lastOrNull()?.let { tabs.getTabLabel(it) }
                // Loose pills (no host: every tab collapsed, or only pinned tabs beside a separate pinned row) are the last units of
                // the strip and sit in a row of their own ([SpareRow]); the end is behind them, in that row, not on the last label
                // (a pinned tab, in the pinned row above). The same when nothing but pinned tabs is left beside a separate pinned row
                // and what arrives is unpinned: the platform's drop of an unpinned tab goes to the row after the pinned one, so the
                // gap opens there, in the spare row, and not at the end of the pinned row.
                val unpinnedAfterPinned = !f.pinned && SpareRow.pinnedRowApart(tabs) && infos.isNotEmpty() && infos.all { it.isPinned }
                if (list.any { it.label == null } || unpinnedAfterPinned) Spacer(null, true, null, true, f.length)
                else if (last != null) Spacer(last, false, null, true, f.length)
                else null
            }
        }
    }

    /** The elements reserved before and after [label], in order, the foreign gap's space among them. */
    fun itemsOf(label: TabLabel): Pair<List<SpaceItem>, List<SpaceItem>> {
        val lead = assignments.filter { it.label === label && it.leading }.mapTo(ArrayList()) { SpaceItem(it.header, it.length) }
        val trail = assignments.filter { it.label === label && !it.leading }.mapTo(ArrayList()) { SpaceItem(it.header, it.length) }
        spacer?.takeIf { it.label === label }?.let { sp -> insertSpacer(if (sp.leading) lead else trail, sp) }
        return lead to trail
    }

    /** True if some pill has no host label (every tab collapsed, or only pinned tabs left beside a separate pinned row). */
    fun hasLoosePills(): Boolean = assignments.any { it.label == null }

    /** True if the foreign gap sits in the loose row ([looseItems]): the spare row beside a separate pinned row is needed for it as for a pill. */
    fun hasLooseSpacer(): Boolean = spacer?.label == null && spacer != null

    /**
     * The pills no label hosts, in order, the foreign gap's space among them: those of an all-collapsed strip, or (pinned tabs in a row
     * of their own) of collapsed groups with no unpinned tab left to host them.
     */
    fun looseItems(): List<SpaceItem> {
        val items = assignments.filter { it.label == null }.mapTo(ArrayList()) { SpaceItem(it.header, it.length) }
        spacer?.takeIf { it.label == null }?.let { insertSpacer(items, it) }
        return items
    }

    private fun insertSpacer(items: MutableList<SpaceItem>, sp: Spacer) {
        val at = when {
            sp.before != null -> items.indexOfFirst { it.header === sp.before.header }.coerceAtLeast(0)
            sp.atEnd || sp.afterPills -> items.size
            else -> 0
        }
        items.add(at, SpaceItem(null, sp.length))
    }

    /** The file whose label hosts the pill of [groupId], and whether on its leading side (tests only). Null if the pill is not hosted. */
    fun pillHost(groupId: String): Pair<VirtualFile?, Boolean>? =
        assignments.firstOrNull { it.header.groupId == groupId }?.let { a ->
            (a.label?.info?.`object` as? VirtualFile) to a.leading
        }

    /** The tab in front of [file] in the strip as of the last [update], or null. Used to re-anchor collapsed groups. */
    fun tabBefore(file: VirtualFile): VirtualFile? = lastOrder.indexOf(file).let { i -> if (i > 0) lastOrder[i - 1] else null }

    fun dispose() {
        space.dispose()
    }
}
