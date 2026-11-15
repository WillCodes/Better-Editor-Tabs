package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef

/**
 * The popups and dialogs [TabGroupsService] needs. The service only knows this interface; `ui/` implements it (`ui.TabGroupsUiImpl`,
 * a project service registered in `plugin.xml` under this interface), so `ui/` depends on `service/` and never the other way round.
 * Tests replace the implementation to answer dialogs headlessly. EDT only.
 */
interface TabGroupsUi {
    /** Opens the group popup for the group of [header], next to the header. */
    fun showGroupPopup(header: HeaderRef)

    /**
     * Asks whether to delete the group shown as [groupName] (its name, or "Unnamed group (Blue)") and close its [tabCount] open
     * tabs in this split, with a "Don't ask again" checkbox. Modal. The service stores the "don't ask again" choice itself.
     */
    fun confirmDelete(groupName: String, tabCount: Int): DeleteAnswer

    /**
     * The group shown as [groupName] has instances in other splits and [tabCount] open tabs in this one: asks whether to move
     * those tabs to the group's instance in another split ([LinkedDeleteAnswer.MOVE]), close them ([LinkedDeleteAnswer.CLOSE]) or do
     * nothing ([LinkedDeleteAnswer.CANCEL], also on Escape). Modal. A "Don't ask again" checkbox, ticked together with Move or Close,
     * answers with [LinkedDeleteChoice.dontAskAgain]; the service then remembers that answer.
     * The default answers Cancel, so a test double that does not care never deletes anything.
     */
    fun askLinkedDelete(groupName: String, tabCount: Int): LinkedDeleteChoice = LinkedDeleteChoice(LinkedDeleteAnswer.CANCEL)

    /**
     * The pointer rests on the header of a collapsed group: after a short delay, show [files] (the group's open tabs, in order)
     * in a list below [header]. Picking an entry calls [TabGroupsService.openFromTabList]. Calling it again for the same header while
     * pending or shown changes nothing.
     */
    fun showTabListOnHover(header: HeaderRef, files: List<VirtualFile>) {}

    /**
     * The pointer left [header] (or pressed it): cancels a pending list; a shown list closes unless the pointer moved into it,
     * in which case it closes when the pointer leaves the list.
     */
    fun hoverEnded(header: HeaderRef) {}

    /**
     * The group shown as [groupName] was brought back (a stash restore or Reopen Closed Tab), but the files in
     * [missing] (their names, in group order) no longer exist and were skipped. [restored] is the number of tabs that did come back; 0
     * means nothing was restored at all. A modal warning. Not called when every file was found.
     */
    fun warnMissingFiles(groupName: String, missing: List<String>, restored: Int) {}

    /**
     * [count] open tabs match the auto-group rule just set on the group shown as [groupName]; asks *"N open tabs match this rule.
     * Move them into "‹name›"?"* (Yes / No). Modal. True for Yes. The default answers No, so a test double that does not care moves
     * nothing.
     */
    fun askMoveMatching(groupName: String, count: Int): Boolean = false

    /**
     * The group shown as [groupName] has no tabs left, in the [case] given. [ruled]: it has an enabled
     * auto-group rule. Without a rule: *"‹name›" is empty. Delete it?* (Delete / Keep); with one: *Keep it so that new matching files
     * join it?* (Keep / Delete). Each with a "Don't ask again" checkbox whose answer the service remembers apart for ruled and unruled
     * groups. Modal. The default answers Keep for a ruled group and Delete for an unruled one, so tests keep the automatic removal.
     */
    fun askDeleteEmpty(groupName: String, ruled: Boolean, case: EmptyCase): EmptyGroupChoice =
        EmptyGroupChoice(if (ruled) EmptyGroupAnswer.KEEP else EmptyGroupAnswer.DELETE)

    /**
     * The temporary split the user closed held empty groups; the groups shown as [moved] (their names, in strip order) went to
     * the split used last, and those shown as [deleted] had no other split to go to and were deleted. One modal warning for all of them.
     * Not called when both are empty. The default does nothing, so tests stay headless.
     */
    fun warnTemporarySplitClosed(moved: List<String>, deleted: List<String>) {}

    /** The context menu of the free part of [split]'s tab strip (*New Group*), at [point] in [component]. */
    fun showStripMenu(split: dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle, component: javax.swing.JComponent, point: java.awt.Point) {}

    companion object {
        fun getInstance(project: Project): TabGroupsUi = project.service()
    }
}

/** The answer to [TabGroupsUi.confirmDelete]. "Don't ask again" only counts together with Delete. */
enum class DeleteAnswer { DELETE, DELETE_AND_STOP_ASKING, CANCEL }

/**
 * How a group came to be empty, for the wording of [TabGroupsUi.askDeleteEmpty]: its last tab was closed, dragged out or pinned, or its
 * popup closed while empty. Every case that would remove a group by itself asks.
 */
enum class EmptyCase { CLOSED, DRAGGED_OUT, PINNED, POPUP_CLOSED }

/** The answer to [TabGroupsUi.askDeleteEmpty]. */
enum class EmptyGroupAnswer { DELETE, KEEP }

/** The reply of [TabGroupsUi.askDeleteEmpty]: the [answer], and whether "Don't ask again" was ticked with it. */
data class EmptyGroupChoice(val answer: EmptyGroupAnswer, val dontAskAgain: Boolean = false)

/** The answer to [TabGroupsUi.askLinkedDelete]. */
enum class LinkedDeleteAnswer { MOVE, CLOSE, CANCEL }

/** The reply of [TabGroupsUi.askLinkedDelete]: the [answer], and whether "Don't ask again" was ticked with it (ignored for Cancel). */
data class LinkedDeleteChoice(val answer: LinkedDeleteAnswer, val dontAskAgain: Boolean = false)
