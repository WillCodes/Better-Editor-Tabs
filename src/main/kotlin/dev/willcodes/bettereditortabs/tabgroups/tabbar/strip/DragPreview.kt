package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.impl.JBEditorTabs
import java.awt.Rectangle
import dev.willcodes.bettereditortabs.tabgroups.model.strip.GapRuns
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration

/**
 * State of the plugin's own drag in this split: the previewed group membership of dragged tabs, the dragged file, and the
 * empty drop slot ([DragGap]) shown for what is being dragged.
 *
 * Owns plain state plus the [DragGap] component; it changes nothing in the platform. The coordinator ([TabBarAdapter]) reads it when
 * it decides where pills go and which colour tabs get. Internal API wrapped: none directly ([DragGap] wraps `JBTabsImpl.tabPainter`).
 *
 * EDT only.
 */
internal class DragPreview(tabs: JBEditorTabs) {
    /** Preview membership: file -> group id, null id = "outside every group". */
    private val membership = LinkedHashMap<VirtualFile, String?>()

    /** The file of the plugin's own tab drag in progress ([begin]), or null. */
    var draggedFile: VirtualFile? = null
        private set

    /** The tabs in front of each expanded group when the drag began. */
    private val before = HashMap<String, Set<VirtualFile>>()

    /** For each parked group (its only tab is the dragged one), whether the drop now puts the dragged tab in front of its pill. */
    private val draggedInFront = HashMap<String, Boolean>()

    /** See [draggedInFront]; set by the drag session with every new landing ([setParkedSides]). Null: not decided (old hosting). */
    fun draggedInFrontOf(groupId: String): Boolean? = draggedInFront[groupId]

    /** The drag session's decision for the parked groups (group id -> the dragged tab stands in front of its pill). */
    fun setParkedSides(sides: Map<String, Boolean>) {
        draggedInFront.clear()
        draggedInFront.putAll(sides)
    }

    /** The empty slot component and what it covers: the tabs and the pills of its groups. */
    val gap = DragGap(tabs)
    private var gapFiles: Set<VirtualFile> = emptySet()
    private var gapPills: Set<String> = emptySet()

    /** Groups whose pill is hidden because the group is being dragged ([setGap]). */
    val gapPillGroups: Set<String> get() = gapPills

    /** True while some file has a previewed membership. */
    val hasMembershipPreview: Boolean get() = membership.isNotEmpty()

    fun isPreviewed(file: VirtualFile): Boolean = membership.containsKey(file)

    /** True while some file is previewed as a member of group [groupId]. */
    fun joins(groupId: String): Boolean = membership.values.any { it == groupId }

    /** Treats [file] as being in group [groupId] (null = no group) until [clear]. Replaces an earlier preview of the same file. */
    fun setMembership(file: VirtualFile, groupId: String?) {
        membership[file] = groupId
    }

    /**
     * The plugin's own tab drag of [file] begins: remembers which tabs stand in front of every expanded group of [groups] in the
     * strip order [visible], so that a group whose only tab is dragged away keeps its pill where it was.
     */
    fun begin(file: VirtualFile, groups: List<GroupDecoration>, visible: List<VirtualFile>) {
        draggedFile = file
        before.clear()
        for (g in groups) {
            // A partly shown group whose peek is the dragged tab parks its pill like a group whose only tab is dragged
            if (g.collapsed && g.peek != file) continue
            val first = visible.indexOfFirst { it in g.members }
            if (first >= 0) before[g.id] = visible.subList(0, first).toHashSet()
        }
    }

    /** The tabs that stood in front of expanded group [groupId] when the drag began, if it had members in view. */
    fun tabsBefore(groupId: String): Set<VirtualFile>? = before[groupId]

    /** Sets what the drop slot covers (see [layoutGap]). Empty arguments remove it. */
    fun setGap(files: Collection<VirtualFile>, pillGroups: Collection<String>) {
        gapFiles = files.toHashSet()
        gapPills = pillGroups.toHashSet()
    }

    /** Ends every preview and the gap. Returns false if there was nothing to end. */
    fun clear(): Boolean {
        val had = membership.isNotEmpty() || draggedFile != null || gap.isShowingGap
        membership.clear()
        draggedFile = null
        before.clear()
        draggedInFront.clear()
        gapFiles = emptySet()
        gapPills = emptySet()
        return had
    }

    /**
     * The files that count as members of [g] for pill placement: the model, with the drag preview applied. A group whose visible
     * members are all previewed away has no member left: its pill is hosted as an empty group's pill.
     */
    fun effectiveMembers(g: GroupDecoration): Set<VirtualFile> {
        val real = g.members.toHashSet()
        if (membership.isEmpty() || g.collapsed) return real
        val effective = HashSet<VirtualFile>()
        for (f in real) if (!membership.containsKey(f) || membership[f] == g.id) effective += f
        for ((f, id) in membership) if (id == g.id) effective += f
        return effective
    }

    /** The group a tab belongs to for pill placement and tint: the drag preview if there is one, else the model. */
    fun effectiveGroupId(file: VirtualFile, groups: List<GroupDecoration>): String? =
        if (membership.containsKey(file)) membership[file] else groups.firstOrNull { file in it.members }?.id

    /**
     * Places the drop slot over the current rectangles of the gap's tabs and pills, taken from [units] (empty rectangles are
     * scrolled out of view). Returns true if the slot is showing, i.e. the pills have to be raised above it again.
     */
    fun layoutGap(horizontal: Boolean, units: () -> List<StripUnit>, extraRuns: List<Rectangle> = emptyList()): Boolean {
        if (gapFiles.isEmpty() && gapPills.isEmpty() && extraRuns.isEmpty()) {
            gap.setRuns(emptyList())
            return false
        }
        val rects = units().mapNotNull { u ->
            when (u) {
                is StripUnit.Tab -> if (u.file in gapFiles) u.rect else null
                is StripUnit.Pill -> if (u.groupId in gapPills) u.rect else null
                is StripUnit.Count -> if (u.groupId in gapPills) u.rect else null
            }
        }
        gap.setRuns(GapRuns.merge(rects, horizontal) + extraRuns)
        return true
    }
}
