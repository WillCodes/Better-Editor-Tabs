package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EmptyPillSpots
import dev.willcodes.bettereditortabs.tabgroups.model.strip.MoveUnit
import dev.willcodes.bettereditortabs.tabgroups.model.strip.StripItem
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader

private val LOG = logger<TabBarDnd>()

/**
 * Moves a whole group instance from this split to [target], merging with the target's instance of the same
 * group if it has one (the merged tabs go where the existing instance is; otherwise at [boundary] of [targetUnits]).
 *
 * The platform has no "move editor to another split" API that keeps the tab: its own drag and "Move to opposite group" close the
 * file in the source and open it again in the target with `CLOSING_TO_REOPEN` set on the file, which tells editors to keep their
 * state (`MoveEditorToOppositeTabGroupAction`, `EditorTabbedContainerDragOutDelegate.dragOutFinished`). We do the same per file.
 * That call is `@ApiStatus.Internal` (`FileEditorManagerEx.openFile(file, window, FileEditorOpenOptions)`); caret/scroll restore is
 * expected to work like the platform's own tab drag but is unverified. The Reopen Closed Tab entry that each close adds to the source split is removed again
 * (`TabClose.closeForMove`). The per-file move is [transferFile], shared with the tab drag ([moveTabToSplit]).
 *
 * A group that was dragged as its collapsed header comes back as it was: [expanding] is its id when it was expanded, and the
 * arrival plays the expand animation in the target (nothing else animates); [selectedBefore] is the tab that was selected when the
 * drag began (the drag moved the selection off the group), selected in the target again if it was one of the group's.
 *
 * Order of work: leave the source model first (so no header/hidden tab refers to files that are about to be closed), open the
 * files in the target, re-order the target in one go, then hand the merged group to the target model.
 */
internal fun TabBarDnd.moveGroupToSplit(
    unit: MoveUnit<VirtualFile>,
    target: TabBarDnd,
    targetUnits: List<MoveUnit<VirtualFile>>,
    boundary: Int,
    freezes: List<StripFreeze> = freezeStrips(target),
    selectedBefore: VirtualFile? = adapter.selectedFile(),
    expanding: String? = null,
    copy: Boolean = false,
    focus: Boolean = true,
) {
    runMove(target, freezes) { moveNow(unit, target, targetUnits, boundary, selectedBefore, expanding, copy, focus) }
}

/**
 * Pictures of this strip and [target]'s, shown over them until [releaseLater] ([StripFreeze]). The platform lets the event queue run
 * while it closes and opens the editors, so both strips would otherwise be painted half-way through the move. A drop takes them
 * *before* it puts the source strip back and closes the target's drop gap, so the user sees the strips as they were at the release
 * and then the result, nothing in between.
 */
internal fun TabBarDnd.freezeStrips(target: TabBarDnd): List<StripFreeze> = listOfNotNull(freeze(), target.freeze())

/** A picture of this strip only (a move into a split that has no strip yet, [moveGroupToNewSplit]). */
internal fun TabBarDnd.freezeOwn(): List<StripFreeze> = listOfNotNull(freeze())

/** Removes [freezes] once the events the move left in the queue (late `fileOpened`, layouts) have run. */
internal fun releaseLater(freezes: List<StripFreeze>) {
    if (freezes.isEmpty()) return
    ApplicationManager.getApplication().invokeLater({ freezes.forEach { it.release() } }, ModalityState.any())
}

/** A [StripFreeze] over this split's tab rows (null if the strip is not showing). */
private fun TabBarDnd.freeze(): StripFreeze? {
    val screen = stripScreenRect() ?: return null
    val origin = origin() ?: return null
    screen.translate(-origin.x, -origin.y)
    return StripFreeze.over(tabs.component, screen)
}

internal fun TabBarDnd.moveNow(
    unit: MoveUnit<VirtualFile>,
    target: TabBarDnd,
    targetUnits: List<MoveUnit<VirtualFile>>,
    boundary: Int,
    selectedBefore: VirtualFile?,
    expanding: String?,
    copy: Boolean = false,
    focus: Boolean = true,
) {
    val groupId = unit.groupId ?: return
    val files = unit.keys
    // an empty instance (no tab, its pill alone) moves too; it has no files, and its pill is placed at the drop
    val empty = files.isEmpty()
    if (empty && !unit.isEmptyPill) return
    val sourceGroups = model.groups()
    val group = sourceGroups.firstOrNull { it.id == groupId } ?: return
    val selected = selectedBefore
    val targetWindow = target.adapter.window

    // Where do the files go in the target?
    val existingUnit = targetUnits.firstOrNull { it.groupId == groupId }
    val merged: List<VirtualFile>
    val targetOrder: List<VirtualFile>
    // the target's order with the pills of its empty groups in it, to keep them in place around the arrival
    val targetSequence: List<StripItem<VirtualFile>>
    if (existingUnit != null) {
        merged = existingUnit.keys + files.filter { it !in existingUnit.keys }
        targetOrder = targetUnits.flatMap { if (it === existingUnit) merged else it.keys }
        targetSequence = targetUnits.flatMap { if (it === existingUnit) merged.map { f -> StripItem.Tab(f) } else it.items() }
    }
    else {
        merged = files
        targetOrder = targetUnits.take(boundary).flatMap { it.keys } + files + targetUnits.drop(boundary).flatMap { it.keys }
        val arriving: List<StripItem<VirtualFile>> = if (empty) listOf(StripItem.EmptyPill(groupId)) else files.map { StripItem.Tab(it) }
        targetSequence = targetUnits.take(boundary).flatMap { it.items() } + arriving + targetUnits.drop(boundary).flatMap { it.items() }
    }
    val targetGroups = target.model.groups()

    // 1. source leaves: the group disappears from the source model (this also shows its hidden tabs). A copy leaves the source as it is.
    if (!copy) model.commit(sourceGroups.filter { it.id != groupId })
    // 2. target: nothing may be hidden while files are added (index spaces of the platform mix hidden and visible tabs).
    if (targetGroups.any { it.collapsed }) target.adapter.setGroups(targetGroups.map { it.copy(collapsed = false) })

    for ((i, file) in files.withIndex()) {
        transferFile(file, target, close = !copy, keepHistory = false, disposeSource = i == files.lastIndex)
    }

    val open = StripReader.allFiles(target.tabs)
    val order = (targetOrder.filter { it in open }.distinct() + open.filter { it !in targetOrder })
    target.adapter.applyOrder(order)
    target.adapter.placeEmptyPills(EmptyPillSpots.derive(targetSequence))
    val mergedOpen = order.filter { it in merged }
    target.model.acceptGroup(
        (targetGroups.firstOrNull { it.id == groupId } ?: group).copy(members = mergedOpen, collapsed = group.collapsed, peek = null), expanding, // arrives collapsed, no peek
    )
    // the arriving empty pill is known to the target's strip only now: it stands at the drop place (a merge keeps the existing instance's)
    if (empty && existingUnit == null) target.adapter.placeEmptyPills(EmptyPillSpots.derive(targetSequence))
    if (focus) focusArrival(targetWindow, if (selected != null && selected in files && !group.collapsed) selected else null)
    LOG.info("group $groupId ${if (copy) "copied" else "moved"}: ${files.size} files${if (empty) " (an empty instance)" else ""} to another split, merged=${existingUnit != null}")
}

/**
 * Like the platform's drop of a tab, the split a group was dropped into becomes the current one (new files open there, actions act on
 * it), with [selected] (the tab that was selected when the drag began, if it is one of the group's and the group is expanded) selected
 * and focused. Closing the moved tabs in the source and the platform's own focus requests while the split or window is made can leave
 * the source current, so it is set again once the events the move queued have run. `EditorWindow.setAsCurrentWindow(boolean)` is
 * public and identical in 262 and 263.
 */
internal fun focusArrival(targetWindow: EditorWindow, selected: VirtualFile?) {
    fun apply() {
        if (targetWindow.isDisposed) return
        if (selected != null && targetWindow.getComposite(selected) != null) targetWindow.setSelectedComposite(selected, true)
        targetWindow.setAsCurrentWindow(true)
    }
    apply()
    ApplicationManager.getApplication().invokeLater({ apply() }, ModalityState.any())
}
