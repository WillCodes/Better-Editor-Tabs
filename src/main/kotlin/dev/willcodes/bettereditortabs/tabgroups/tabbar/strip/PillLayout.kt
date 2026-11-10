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

/** Where the space for a group that is being dragged in from another split opens: before a tab, before a collapsed group's pill, or at the end. */
internal sealed class ForeignAnchor {
    data class BeforeTab(val file: VirtualFile) : ForeignAnchor()
    data class BeforeCollapsedGroup(val groupId: String) : ForeignAnchor()
    object AtEnd : ForeignAnchor()
}

/** The space [length] (along the strip) that a group dragged in from another split takes in this strip, and where. */
internal class ForeignGap(val anchor: ForeignAnchor, val length: Int)

/** One element reserved next to a label (or in the empty strip): a pill (`header` set) or the foreign gap's space. */
internal class SpaceItem(val header: GroupHeader?, val length: Int)

/** The foreign gap resolved against the current hosting: [label] null = in the empty strip. [before] null = at the front, unless [atEnd]. */
internal class Spacer(val label: TabLabel?, val leading: Boolean, val before: PillAssignment?, val atEnd: Boolean, val length: Int)

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
) {
    val space = TabLabelSpace(tabs)

    /** Where each pill is hosted, as of the last [update]. */
    var assignments: List<PillAssignment> = emptyList()
        private set

    /** The space for a group dragged in from another split (see [ForeignGap]); set by [TabBarAdapter.setForeignGap]. */
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
        val dragged = platformDraggedFile()
        val dragging = dragged != null
        val list = ArrayList<PillAssignment>()
        if (!tabs.isHideTabs) {
            for (g in groups) {
                val header = headerOf(g.id) ?: continue
                val members = preview.effectiveMembers(g)
                val hasVisibleMember = files.any { it != null && it in members }
                // every visible member is being dragged away by the plugin's drag: the group is parked, its pill stays behind
                val parked = !g.collapsed && !hasVisibleMember && files.any { it != null && it in g.members }
                val before: Set<VirtualFile> = if (g.collapsed && !hasVisibleMember) {
                    val snapshot = if (dragging) collapsedBefore[g.id] else null
                    snapshot ?: PillHosting.tabsBefore(files, collapse.anchorOf(g.id)).also { if (!dragging) collapsedBefore[g.id] = it }
                }
                else if (parked) preview.tabsBefore(g.id) ?: files.subList(0, files.indexOfFirst { it != null && it in g.members }).filterNotNull().toSet()
                else emptySet()
                val host = PillHosting.hostFor(files, if (parked) preview.draggedFile else dragged, { it in members }, before)
                val label = host?.let { tabs.getTabLabel(infos[it.index]) }
                if (host != null && label != null && (hasVisibleMember || g.collapsed || parked)) {
                    list += PillAssignment(header, label, host.leading, header.pillLength())
                }
                else if (g.collapsed && infos.isEmpty() && collapse.hasAnchor(g.id)) {
                    list += PillAssignment(header, null, true, header.pillLength())
                }
            }
        }
        if (!dragging) collapsedBefore.keys.retainAll(groups.filter { it.collapsed }.map { it.id }.toSet())
        assignments = list
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
            changed = space.set(label, if (horizontal) lead.sumOf { it.length } else 0, if (horizontal) trail.sumOf { it.length } else 0) || changed
        }
        return space.releaseExcept(keep) || changed
    }

    private fun resolveSpacer(infos: List<com.intellij.ui.tabs.TabInfo>, list: List<PillAssignment>): Spacer? {
        val f = foreign ?: return null
        if (tabs.isHideTabs) return null
        return when (val a = f.anchor) {
            is ForeignAnchor.BeforeTab -> {
                val label = infos.firstOrNull { it.`object` == a.file }?.let { tabs.getTabLabel(it) } ?: return null
                Spacer(label, true, null, false, f.length)
            }
            is ForeignAnchor.BeforeCollapsedGroup -> {
                val pill = list.firstOrNull { it.header.groupId == a.groupId } ?: return null
                Spacer(pill.label, pill.leading, pill, false, f.length)
            }
            ForeignAnchor.AtEnd -> {
                val last = infos.lastOrNull()?.let { tabs.getTabLabel(it) }
                if (last != null) Spacer(last, false, null, true, f.length)
                else if (list.all { it.label == null }) Spacer(null, true, null, true, f.length)
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

    /** The pills of an all-collapsed strip (no label hosts them), in order, the foreign gap's space among them. */
    fun looseItems(): List<SpaceItem> {
        val items = assignments.filter { it.label == null }.mapTo(ArrayList()) { SpaceItem(it.header, it.length) }
        spacer?.takeIf { it.label == null }?.let { insertSpacer(items, it) }
        return items
    }

    private fun insertSpacer(items: MutableList<SpaceItem>, sp: Spacer) {
        val at = when {
            sp.before != null -> items.indexOfFirst { it.header === sp.before.header }.coerceAtLeast(0)
            sp.atEnd -> items.size
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
