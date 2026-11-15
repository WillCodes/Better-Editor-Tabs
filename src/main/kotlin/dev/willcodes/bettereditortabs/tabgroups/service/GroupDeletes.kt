package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteStep
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteTrigger
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.groups.deleteStep
import dev.willcodes.bettereditortabs.tabgroups.model.groups.linkedMoveTarget
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar

/**
 * Delete Group for [TabGroupsService], which delegates to it: which question to ask ([requestDelete]),
 * the Move / Close / Cancel choice for a group that also lives in other splits, and Move itself ([moveToLinked]). The deletion proper
 * (closing the tabs, recording the group for Reopen Closed Tab) is [GroupComeback.deleteGroup]. Shares the service's [state];
 * [refresh] is the service's own. EDT only.
 */
internal class GroupDeletes(
    private val project: Project,
    private val state: GroupsState<SplitHandle, VirtualFile>,
    private val comeback: GroupComeback,
    private val refresh: (SplitHandle) -> Unit,
) {
    private val tabBar get() = project.service<TabBar>()
    private val settings get() = TabGroupsSettings.getInstance()

    /** See [TabGroupsService.requestDelete]. */
    fun requestDelete(split: SplitHandle, groupId: String, trigger: DeleteTrigger) {
        val group = state.group(groupId) ?: return
        val instance = state.instance(split, groupId) ?: return
        val tabCount = tabBar.openTabs(split, instance.members).size
        val ui = TabGroupsUi.getInstance(project)
        val name = groupDisplayName(group.name, group.color)
        when (deleteStep(tabCount, settings.confirmsDelete(trigger), linkedSplits(split, groupId).isNotEmpty())) {
            DeleteStep.DELETE_NOW -> {}
            DeleteStep.CONFIRM -> when (ui.confirmDelete(name, tabCount)) {
                DeleteAnswer.CANCEL -> return
                DeleteAnswer.DELETE_AND_STOP_ASKING -> settings.stopConfirmingDelete(trigger)
                DeleteAnswer.DELETE -> {}
            }
            DeleteStep.LINKED_CHOICE -> when (linkedDeleteAnswer(name, tabCount)) {
                LinkedDeleteAnswer.CANCEL -> return
                LinkedDeleteAnswer.CLOSE -> {}
                LinkedDeleteAnswer.MOVE -> {
                    moveToLinked(split, groupId)
                    return
                }
            }
        }
        deleteInstance(split, groupId)
    }

    /** The remembered answer ("Don't ask again"), or the dialog's; a "Don't ask again" ticked with Move or Close is remembered. */
    private fun linkedDeleteAnswer(groupName: String, tabCount: Int): LinkedDeleteAnswer {
        settings.linkedDeleteChoice?.let { return it }
        val choice = TabGroupsUi.getInstance(project).askLinkedDelete(groupName, tabCount)
        if (choice.dontAskAgain) settings.rememberLinkedDelete(choice.answer)
        return choice.answer
    }

    /** The live splits other than [split] that have an instance of [groupId]. */
    private fun linkedSplits(split: SplitHandle, groupId: String): List<SplitHandle> {
        val live = tabBar.splits().toSet()
        return state.splits().filter { it != split && it in live && state.instance(it, groupId) != null }
    }

    /**
     * "Move": the tabs of [split]'s instance go to the instance in the most recently focused other split. Re-reads the state
     * (the dialog was modal). The files that are open in the target already are just closed here (a normal close); the rest moves as a
     * group move (no Reopen Closed Tab history, merged at the end of the target's instance, which keeps its collapsed flag). Nothing
     * is done if no other split has the group any more.
     */
    private fun moveToLinked(split: SplitHandle, groupId: String) {
        val instance = state.instance(split, groupId) ?: return
        val target = linkedMoveTarget(tabBar.recentSplits(), linkedSplits(split, groupId), split) ?: return
        val duplicates = tabBar.openTabs(target, tabBar.openTabs(split, instance.members))
        if (duplicates.isNotEmpty()) {
            for (file in duplicates) state.removeFromGroup(split, file)
            refresh(split)
            tabBar.closeTabs(split, duplicates)
        }
        if (state.instance(split, groupId) == null) return
        tabBar.moveGroup(split, groupId, target)
    }

    /**
     * Removes the instance, then closes its open tabs without Reopen Closed Tab entries and records the group, so that Reopen Closed
     * Tab brings it back ([GroupComeback.deleteGroup]). The state is re-read there because the confirmation is modal and may
     * have let things change. The instance goes first and the split is refreshed, so no tab is still hidden while it closes and the
     * tab bar no longer lists the files ([TabGroupsService.tabClosed] is not reported for them).
     */
    private fun deleteInstance(split: SplitHandle, groupId: String) = comeback.deleteGroup(split, groupId)
}
