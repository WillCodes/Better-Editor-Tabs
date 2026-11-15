package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PillCell
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EmptyPillSpots
import dev.willcodes.bettereditortabs.tabgroups.model.strip.ExpandSlot
import dev.willcodes.bettereditortabs.tabgroups.model.strip.Landing
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripCell
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripItem
import dev.willcodes.bettereditortabs.tabgroups.model.strip.TabCell
import dev.willcodes.bettereditortabs.tabgroups.model.strip.files
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripUnit
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle

private val LOG = logger<TabDragSession>()

/**
 * One tab drag on one strip, from the moment the pointer is over it to the drop, the move to another strip or the cancel
 * (DRAG-0..DRAG-7): the placeholder, the live re-order and previewed membership, the settle loop that picks the slot under the pointer,
 * the hold-to-join timer ([DragHold]), the restore of the original state and the drop result. The gesture (press, threshold, release,
 * Esc, hand-over to the platform) is [TabDragController]'s; which strip the pointer is over, and the floating picture across strips,
 * is [CrossSplitTabDrag]'s.
 *
 * The session runs on the [target] strip for the carried [dragged] tab, which comes from `dragged.source`. Three cases:
 * - **own strip** (`target === dragged.source`): the tab's place is the empty slot (`StripDragSurface.setDragGap`), the strip is
 *   re-ordered live under it, membership is previewed (pill and tint) and only committed on release ([commit]);
 * - **another split's strip, tab not open there** (DRAG-5): the strip has no tab for it, so the slot is the foreign gap
 *   (`StripDragSurface.setForeignGap`, sized like the tab in that strip's orientation by [ForeignGapSession.lengthIn], as for a group
 *   drag) that opens in front of the cell after the slot and moves with the pointer; nothing in the strip is re-ordered and no
 *   membership is previewed (there is no tab to tint), the picture is tinted instead. Dropping moves the file ([moveTabToSplit]);
 * - **another split's strip, file already open there**: as the own strip (that tab is the one that moves), with the picture made from
 *   the dragged tab's text and icon; dropping re-orders and re-groups it there and closes the source tab ([moveTabToSplit]).
 * [OwnGroupStay] (DRAG-3) only applies to the own strip: in another split the tab is no member of any group.
 *
 * Expand on hold (DRAG-6, Stage 11.5 fix rounds 2-3): the pointer held for the DRAG-3 hold right behind a collapsed group's unit (behind
 * its pill; a partly shown group's unit ends with its "+N" count), the same hold and zone as behind an expanded group's last tab, expands
 * the group ([expandForDrag], the COLL-7 animation, the group's tint): the placeholder becomes the group's first slot, right behind its
 * header, and until the tab leaves the group every slot between its header and its end counts as inside, the trailing one without a
 * further hold ([TabDropPlanner.expandedInto], the rules of [dev.willcodes.bettereditortabs.tabgroups.model.strip.OwnGroupStay]). The
 * group **stays expanded**: leaving it, Esc, a hand-over or a drop anywhere keep it so ([TabBarDnd.expandedByDrag], committed when the drag
 * ends; a drop on this strip folds it into its own result, [commit]).
 *
 * A session can be [suspend]ed (strip back as it was, placeholder gone) and [resume]d, which the home session does while the pointer
 * is over another split's strip; a session for another strip is simply dropped ([restoreOriginal], [end]).
 *
 * Wraps `JBEditorTabs` (via [TabBarDnd]), `TabBarAdapter` drag APIs and the platform's drag picture ([DragImages]). EDT only.
 */
internal class TabDragSession(private val target: TabBarDnd, val dragged: DraggedTab, private val feedback: GroupDragFeedback) {
    /** What a drop leaves behind: the landing the strip shows and the state the drag began with. Passed to [commit]. */
    class Outcome(
        val applied: TabDropPlanner.Applied?,
        val groups: List<GroupDecoration>,
        val unchangedOrder: List<VirtualFile>,
        /** The strip as it was when the drag began and as the drop leaves it, with the pills of empty groups (DRAG-8 keeps a pill's place). */
        val origSeq: List<StripItem<VirtualFile>> = emptyList(),
        val finalSeq: List<StripItem<VirtualFile>> = emptyList(),
    )

    private val tabs get() = target.tabs
    private val adapter get() = target.adapter
    private val source get() = dragged.source
    private val file get() = dragged.file

    /** This session is on another split's strip. */
    val foreign: Boolean = target !== dragged.source

    /** The strip has a tab of [file] that moves with the drag (the own strip, or a split that has the file open already). */
    private var hasTab = true

    private var active = false
    private var origFlat: List<VirtualFile> = emptyList()

    /** [origFlat] with the pills of empty groups in it (RULE-6): they have no file, so the order alone does not say where they stand. */
    private var origSeq: List<StripItem<VirtualFile>> = emptyList()
    private var origGroups: List<GroupDecoration> = emptyList()
    private var applied: TabDropPlanner.Applied? = null

    /** The order the strip shows now (re-ordering is skipped when the slot change does not change it). */
    private var shownFlat: List<VirtualFile> = emptyList()
    private var shownSeq: List<StripItem<VirtualFile>> = emptyList()

    /** The slot the placeholder sits in now (index into the cells without the dragged tab). */
    private var slot = 0
    private var gapRoom = 0
    private var lastPointer: Point? = null
    private val hold = DragHold { lastPointer?.let { refresh(it) } }

    private val planner = TabDropPlanner(target, hold)

    /** COLL-8: the collapsed (or partly shown) group that is expanded for the rest of this drag (a hold behind its unit completed), or null. */
    private var expanded: String? = null

    /** What the slot rules last decided, to tell a changed decision from a repeated one. */
    private var lastResult: Landing? = null

    /** The group whose colour the dragged picture shows now (the landing group). */
    private var tintedAs: String? = null

    /** The tab of [file] in the target strip, if the strip has one. */
    private fun tabInTarget(): TabInfo? = if (!foreign) dragged.info else StripReader.infoOf(tabs, file)

    /**
     * Starts the session on the strip with the pointer at [screen]: the placeholder and the first picture. False (and nothing changed) if
     * the strip cannot show it (the own tab is not laid out). [keepStay]: continue the own-group rule of an earlier session ([resume]).
     */
    fun begin(screen: Point, keepStay: Boolean = false): Boolean {
        if (!target.isShowing) return false
        adapter.finishCollapseAnimations() // a drag starts from a settled strip (COLL-7)
        target.validateNow()
        val own = tabInTarget()
        hasTab = own != null
        if (own?.isHidden == true) return false
        val strip = adapter.stripUnits()
        // DRAG-6: a group this drag expanded earlier (on this strip, before the pointer went to another one) is still expanded
        origGroups = target.model.groups().map { if (it.id in target.expandedByDrag) it.copy(collapsed = false, peek = null) else it }
        origFlat = target.reorder.completeOrder(TabDropPlanner.flatten(strip, origGroups))
        shownFlat = origFlat
        origSeq = TabDropPlanner.sequenceOf(strip, origGroups)
        shownSeq = origSeq
        applied = null
        expanded = null
        lastResult = null
        tintedAs = null
        hold.reset()
        lastPointer = screen
        if (hasTab) {
            val at = strip.indexOfFirst { it is StripUnit.Tab && it.file == file }
            if (at < 0 || strip[at].rect.isEmpty) return false
            // the cells in front of the tab (a partly shown group's peek and count are no cells of their own, COLL-8)
            slot = TabDropPlanner.slotOf(strip, file, parkedGroups) ?: return false
            if (!keepStay) planner.start(startGroup())
            adapter.drag.beginTabDrag(file)
            adapter.drag.setDragGap(listOf(file), emptyList())
        }
        else {
            gapRoom = gapLength() ?: return false
            if (!keepStay) planner.start(null)
            val cells = TabDropPlanner.cellsOf(strip, file, parkedGroups)
            slot = planner.startSlot(cells, screen)
            openGap(cells, slot)
        }
        if (!snapshot()) {
            adapter.drag.clearDragPreview()
            return false
        }
        active = true
        LOG.info("tab drag started on ${if (foreign) "another split's strip" else "its own strip"}: ${file.name}, slot=$slot, groups=${origGroups.map { it.id }}")
        return true
    }

    /** The own strip again after the pointer was over another split's strip. */
    fun resume(screen: Point): Boolean = begin(screen, keepStay = true)

    /** The strip goes back as it was and the session rests (the pointer is over another strip). */
    fun suspend() {
        restoreOriginal()
        stopTimers()
    }

    /** The room the tab takes in the target strip along its axis: its own length in a strip of the same orientation, else worked out there. */
    private fun gapLength(): Int? {
        val label = dragged.labelSize ?: return null
        val length = if (source.horizontal) label.width else label.height
        return ForeignGapSession.lengthIn(target, DraggedGroupSize(source.horizontal, length, label.width))
    }

    /** Opens the foreign gap in front of the cell that follows slot [at], and lays the strip out. */
    private fun openGap(cells: List<StripCell<VirtualFile>>, at: Int) {
        adapter.drag.setForeignGap(TabDropPlanner.anchorFor(cells, at), gapRoom, dragged.pinned)
        target.validateNow()
    }

    /**
     * The floating picture: what the platform shows for a dragged tab, tinted while previewed into a group (DRAG-7). Made from the tab's
     * own strip, or, over another split's strip, from the tab's text and icon by that strip, with that strip's tint for the group the
     * tab would join. False if it could not be made.
     */
    private fun snapshot(): Boolean {
        val sourceTabs = source.tabs
        val image = if (foreign) {
            val tint = applied?.landing?.groupId?.let { adapter.tintFor(it) }
            DragImages.tab(tabs, dragged.info.text, dragged.info.icon, tint)
        }
        else DragImages.tab(sourceTabs, dragged.info)
        if (image != null) {
            feedback.replace(image, DragImages.sizeOf(image), false)
            return true
        }
        if (foreign) return feedback.size.width > 0 // keep the picture the other strip made
        // the platform's picture is not available: the tab's own label (it looks selected)
        val unit = source.adapter.stripUnits().filterIsInstance<StripUnit.Tab>().firstOrNull { it.file == file } ?: return false
        val label = sourceTabs.getTabLabel(dragged.info) ?: return false
        if (unit.rect.isEmpty) return false
        val rect = Rectangle(unit.rect)
        val fallback = DragOverlay.snapshot(sourceTabs, rect, listOf(label to Rectangle(label.bounds)), DragImages.windowBackground()) ?: return false
        feedback.replace(fallback, Dimension(rect.width, rect.height), false)
        return true
    }

    /** Moves the floating picture so that it is centred on the pointer, as the platform's drag image (`DockManagerImpl.setLocationFrom`). */
    fun moveGhost(screen: Point) {
        lastPointer = screen
        val size = feedback.size
        feedback.moveGhost(Point(screen.x - size.width / 2, screen.y - size.height / 2), target)
    }

    /** The pointer moved to [screen] (while it is on the strip): the slot follows it. */
    fun pointerMoved(screen: Point) {
        refresh(screen)
    }

    /**
     * Works out the slot under the pointer and applies it to the strip if it changed. A change re-lays the strip out, which can move
     * the placeholder (another row, a pill re-hosted); the pointer is judged again against the new layout until the slot settles
     * (at most [SETTLE_STEPS] times), so one mouse event ends with the placeholder where the pointer is.
     */
    private fun refresh(screen: Point) {
        if (!active) return
        repeat(SETTLE_STEPS) { if (!step(screen)) return }
    }

    /** The group the drag began with the tab a member of (the own strip only), for [TabDropPlanner.start]. */
    private fun startGroup(): String? = if (foreign) null else origGroups.firstOrNull { file in it.members }?.id

    /** One evaluation; true if it changed the strip. */
    private fun step(screen: Point): Boolean {
        target.validateNow()
        val open = expanded
        val strip = adapter.stripUnits()
        val cells = TabDropPlanner.cellsOf(strip, file, parkedGroups)
        // the group's tabs are growing under the pointer: the placeholder stays at the group's first slot until they have (a release judges
        // the end state); it is shown inside the group meanwhile
        if (open != null && adapter.collapseAnimator.direction(open) == false) {
            if (applied != null) return false
            apply(cells, Landing(slot, open, open))
            return true
        }
        val gap = if (hasTab) strip.filterIsInstance<StripUnit.Tab>().firstOrNull { it.file == file }?.rect else adapter.drag.foreignGapRect()
        val result = planner.landingFor(gap, cells, dragged.pinned, slot, screen)
        val moved = result.slot != slot
        slot = result.slot
        heldCollapsedGroup(cells, result)?.let {
            expandForDrag(it, screen)
            return true
        }
        if (open != null && result.groupId != open) {
            // DRAG-6 (fix round 3): the group stays expanded; from here on its slots follow the ordinary rules (DRAG-1..3), the tab having
            // left it (coming back to its end needs the hold)
            expanded = null
            planner.start(null)
        }
        if (applied != null && lastResult == result) return false
        if (!hasTab && moved) openGap(cells, slot)
        apply(cells, result)
        return true
    }

    /**
     * The collapsed group that the hold behind its unit has made the tab join ([result] joins it while its cell is a collapsed pill, which
     * only a completed hold does, [dev.willcodes.bettereditortabs.tabgroups.model.strip.DropRules.landing]), or null. Empty groups have
     * their own hold (MEM-5 / RULE-6) and the dragged tab's own parked group is one too (DRAG-8); a pinned tab never joins (DRAG-4).
     */
    private fun heldCollapsedGroup(cells: List<StripCell<VirtualFile>>, result: Landing): String? {
        if (dragged.pinned) return null
        val id = result.groupId ?: return null
        val pill = cells.firstOrNull { it is PillCell && it.groupId == id } as? PillCell ?: return null
        return id.takeIf { pill.collapsed && !pill.empty }
    }

    /** The groups as the strip shows them for this drag: the group that was expanded by a hold behind it is expanded (COLL-8). */
    private fun dragGroups(): List<GroupDecoration> {
        val id = expanded ?: return origGroups
        return origGroups.map { if (it.id == id) it.copy(collapsed = false, peek = null) else it }
    }

    /**
     * The groups the strip is given for the drag: [dragGroups], and a partly shown group whose peek is the dragged tab without the tab once
     * [landing] takes it out of the group (it is an ordinary tab from there on and the group is collapsed fully, COLL-8).
     */
    private fun shownGroups(landing: Landing?): List<GroupDecoration> {
        val base = dragGroups()
        val stays = hasTab && landing != null && origGroups.any { it.collapsed && it.peek == file && landing.groupId == it.id }
        return if (stays) base else base.map { g -> if (g.peek == file) g.copy(peek = null, members = g.members - file) else g }
    }

    /**
     * DRAG-6 (Stage 11.5 fix rounds 2-3, user requests): the hold behind the unit of collapsed (or partly shown) group [id] completed: it
     * expands, animated (COLL-7), with its tint, and **stays expanded** for the rest of the drag and after it, whatever the drop (recorded
     * in [TabBarDnd.expandedByDrag]; the drag's end commits it). The placeholder takes the slot of the group that the pointer at [screen]
     * is at in the group's **expanded** layout (fix round 4: [openingSlot], worked out before the tabs grow, so they grow around it), inside
     * the group ([TabDropPlanner.expandedInto]: also its trailing slot counts as inside without another hold until the tab leaves it).
     */
    private fun expandForDrag(id: String, screen: Point) {
        val before = openingSlot(id, screen)
        expanded = id
        target.expandedByDrag += id
        origGroups = origGroups.map { if (it.id == id) it.copy(collapsed = false, peek = null) else it }
        planner.expandedInto(id)
        adapter.expandForDrag(dragGroups())
        target.validateNow()
        val strip = adapter.stripUnits()
        val cells = TabDropPlanner.cellsOf(strip, file, parkedGroups)
        val pill = cells.indexOfFirst { it is PillCell && it.groupId == id }
        // the k-th slot of the group: right behind its header for 0, else right behind its k-th tab (its tabs are cells in strip order now)
        val tabsAt = cells.indices.filter { (cells[it] as? TabCell)?.groupId == id }
        slot = if (before <= 0 || tabsAt.isEmpty()) pill + 1 else tabsAt[minOf(before, tabsAt.size) - 1] + 1
        if (!hasTab) openGap(cells, slot)
        lastResult = null
        applied = null
        LOG.info("tab drag: group $id expanded for the drag (hold behind its unit), placeholder behind $before of its tabs")
    }

    /**
     * DRAG-6 fix round 4 (user request): how many of group [id]'s tabs stand in front of the placeholder when it expands, from the pointer
     * at [screen] against the group's expanded layout ([ExpandSlot]): its header's end, then its tabs (those open here, in the strip's
     * logical order, not the dragged one) at their natural length along the strip (a hidden label keeps its content's preferred size; in a
     * side strip every tab is a row), and the placeholder's own length. Read before the expand changes the labels. 0 if anything is missing.
     */
    private fun openingSlot(id: String, screen: Point): Int {
        val horizontal = target.horizontal
        val pill = adapter.stripUnits().filterIsInstance<StripUnit.Pill>().firstOrNull { it.groupId == id } ?: return 0
        val start = if (horizontal) pill.rect.x + pill.rect.width else pill.rect.y + pill.rect.height
        val members = origGroups.firstOrNull { it.id == id }?.members?.toHashSet() ?: return 0
        val inOrder = adapter.logicalOrder().filter { it in members && it != file }
        val lengths = inOrder.mapNotNull { f ->
            val label = StripReader.infoOf(tabs, f)?.let { tabs.getTabLabel(it) } ?: return@mapNotNull null
            if (!horizontal) return@mapNotNull maxOf(label.preferredSize.height, label.height)
            // the tab's own length: without the space it reserves for pills now (a partly shown group's peek hosts its header and count)
            val (lead, trail) = adapter.reservedSpace(f) ?: (0 to 0)
            (label.preferredSize.width - lead - trail).coerceAtLeast(0)
        }
        if (lengths.isEmpty()) return 0
        val gap = if (hasTab) {
            dragged.info.let { tabs.getTabLabel(it) }?.let { if (horizontal) it.width else it.height } ?: lengths.first()
        }
        else gapRoom
        val p = target.toTabs(screen)
        return ExpandSlot.choose(start, lengths, gap, if (horizontal) p.x else p.y)
    }

    /** Applies the decision to the strip and works out what the drop leaves ([TabDropPlanner.Applied]): the landing the slot rules chose. */
    private fun apply(cells: List<StripCell<VirtualFile>>, landing: Landing) {
        val groupChanged = applied == null || tintedAs != landing.groupId
        // COLL-8: a partly shown group's peek dropped back where it is (behind its pill, still a member) changes nothing, the member order included
        val stays = hasTab && origGroups.any { it.collapsed && it.peek == file && landing.groupId == it.id }
        val seq = if (stays) origSeq else TabDropPlanner.sequenceOrder(cells, landing, file, dragGroups())
        val flat = if (stays) origFlat else target.reorder.completeOrder(seq.files())
        if (hasTab) {
            // a pill of an empty group (RULE-6) is not in the file order: it stays behind the tab that is in front of it in the sequence
            if (flat != shownFlat || seq != shownSeq) {
                // COLL-8: a peek that leaves its group is an ordinary tab from here on: the group is collapsed fully, without it
                target.reorder.reorder(shownGroups(landing), flat, EmptyPillSpots.derive(seq))
                shownFlat = flat
                shownSeq = seq
            }
            // after the groups were re-applied: the preview (pill placement and tint) follows the previewed membership
            adapter.drag.setDragPreview(file, landing.groupId, parkedSides(cells, landing))
        }
        lastResult = landing
        tintedAs = landing.groupId
        val inFront = (cells.getOrNull(landing.slot) as? PillCell)?.groupId
        applied = TabDropPlanner.Applied(landing, flat, inFront)
        if (groupChanged) {
            target.validateNow()
            snapshot() // the picture carries the live tint (DRAG-7)
        }
    }

    /**
     * The release: judges the release position itself, not only the last drag event, and ends the session (the hold timer goes away; the
     * strip already shows the result). The caller then settles the gesture's own state and calls [commit] (own strip) or moves the
     * file ([moveTabToSplit]).
     */
    fun finishDrop(screen: Point): Outcome {
        // a group that is still growing (or shrinking) after the pointer's last move is judged in its final layout
        if (expanded != null) adapter.finishCollapseAnimations()
        refresh(screen)
        val outcome = Outcome(applied, origGroups, origFlat, origSeq, shownSeq)
        end()
        return outcome
    }

    /** The preview is removed and the model learns about the drop (a group left without a member is removed by the model, MEM-5). */
    fun commit(outcome: Outcome) {
        adapter.drag.clearDragPreview()
        val a = outcome.applied ?: return
        val groups = outcome.groups
        val newGroups = TabDropPlanner.groupsAfterDrop(groups, file, a)
        LOG.info("tab drop: ${file.name} -> ${a.landing.groupId ?: "ungrouped"} at slot ${a.landing.slot}, order changed: ${a.flat != outcome.unchangedOrder}")
        if (newGroups == groups && a.flat == outcome.unchangedOrder) {
            adapter.setGroups(groups)
            return
        }
        // DRAG-8: a group left without a member (this was its only tab) keeps its pill where the tab was, not where the tab landed
        val kept = TabDropPlanner.pillsKept(newGroups, groups, file, outcome.origSeq, outcome.finalSeq, a.inFrontOfPill)
        target.model.commit(newGroups)
        if (kept.isNotEmpty()) adapter.placeEmptyPills(kept)
    }

    /**
     * DRAG-8: the groups whose only tab is the dragged one (their pill is parked where the group was), and (COLL-8) the partly shown groups
     * whose peek is the dragged tab: it can leave the group (dropped outside its unit) or stay (dropped back behind the pill, where it is a
     * member at the group's trailing slot, [dev.willcodes.bettereditortabs.tabgroups.model.strip.OwnGroupStay]), and the group stays collapsed.
     */
    private val parkedGroups: Set<String> get() = origGroups.filter { it.members == listOf(file) || (it.collapsed && it.peek == file) }.mapTo(HashSet()) { it.id }

    /**
     * DRAG-8: for each group whose only tab is the dragged one (its pill is parked where the group was), whether [landing] puts the tab in
     * front of that pill: the landing slot is at or before the pill's cell. The strip hosts the parked pill accordingly, so it shows what
     * the slot says (user report, round 3: otherwise the strip and the slot disagreed and the slot flipped on every step).
     */
    private fun parkedSides(cells: List<StripCell<VirtualFile>>, landing: Landing): Map<String, Boolean> {
        val parked = parkedGroups
        if (parked.isEmpty()) return emptyMap()
        val sides = HashMap<String, Boolean>()
        for (id in parked) {
            val at = cells.indexOfFirst { it is PillCell && it.groupId == id }
            if (at >= 0) sides[id] = landing.slot <= at
        }
        return sides
    }

    /** The target strip's placeholder and previews go away (the strip keeps the order it shows). */
    fun clearPreview() {
        adapter.drag.clearDragPreview()
    }

    /** Back to the order and membership the drag started with, preview and gap removed. */
    fun restoreOriginal() {
        adapter.drag.clearDragPreview()
        if (shownFlat != origFlat || shownSeq != origSeq || expanded != null) {
            // the groups as the drag began, but a group the drag expanded stays expanded (DRAG-6, [origGroups] has it so)
            target.reorder.reorder(origGroups, origFlat, EmptyPillSpots.derive(origSeq))
            shownFlat = origFlat
            shownSeq = origSeq
        }
        if (expanded != null) planner.start(startGroup())
        expanded = null
        lastResult = null
        tintedAs = null
        applied = null
    }

    /** Stops the hold timer (the strip is going away, or the session ends). */
    fun stopTimers() {
        active = false
        hold.reset()
    }

    /** Ends the session: the hold timer goes away. Does not touch the strip or the picture. */
    fun end() {
        stopTimers()
        applied = null
        lastPointer = null
    }

    companion object {
        /** Can a session run on [dnd] for [file]? Not if the strip is not showing, or the file is open there in a tab that is not showing. */
        fun accepts(dnd: TabBarDnd, file: VirtualFile): Boolean {
            if (dnd.stripScreenRect() == null) return false
            val own = StripReader.infoOf(dnd.tabs, file)
            return own == null || !own.isHidden
        }

        /** How often one mouse event may re-evaluate the slot after the previous evaluation changed the layout. */
        private const val SETTLE_STEPS = 4
    }
}
