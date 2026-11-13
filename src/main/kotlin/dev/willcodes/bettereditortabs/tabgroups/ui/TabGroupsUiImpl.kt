package dev.willcodes.bettereditortabs.tabgroups.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.service.DeleteAnswer
import dev.willcodes.bettereditortabs.tabgroups.service.LinkedDeleteAnswer
import dev.willcodes.bettereditortabs.tabgroups.service.LinkedDeleteChoice
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsUi
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef
import dev.willcodes.bettereditortabs.tabgroups.ui.popup.GroupPopup
import dev.willcodes.bettereditortabs.tabgroups.ui.popup.TabListPopup

/** The real popups and dialogs behind [TabGroupsUi]; registered as the project service for that interface in `plugin.xml`. */
class TabGroupsUiImpl(private val project: Project) : TabGroupsUi {
    private val tabList = TabListPopup(project)

    override fun showGroupPopup(header: HeaderRef) {
        tabList.cancel() // a pending or shown hover list gives way to the group popup
        GroupPopup.show(project, header)
    }

    override fun showTabListOnHover(header: HeaderRef, files: List<VirtualFile>) = tabList.hoverStarted(header, files)

    override fun hoverEnded(header: HeaderRef) = tabList.hoverEnded(header)

    /**
     * LIFE-3a: three buttons, Merge Tabs (the default, LIFE-3a's Move), Close Tabs, Cancel, and a "Don't ask again" box that only counts with Move or Close. `MessageDialogBuilder.yesNoCancel` maps them to yes, no and cancel;
     * Escape and the window's close button answer cancel.
     */
    override fun askLinkedDelete(groupName: String, tabCount: Int): LinkedDeleteChoice {
        var stopAsking = false
        val option = object : DoNotAskOption.Adapter() {
            override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                stopAsking = isSelected && (exitCode == Messages.YES || exitCode == Messages.NO)
            }
        }
        val answer = MessageDialogBuilder
            .yesNoCancel(
                BetterEditorTabsBundle.message("delete.title"),
                BetterEditorTabsBundle.message("delete.linked.message", groupName, tabCount),
            )
            .yesText(BetterEditorTabsBundle.message("delete.linked.move"))
            .noText(BetterEditorTabsBundle.message("delete.linked.close"))
            .cancelText(BetterEditorTabsBundle.message("delete.linked.cancel"))
            .icon(Messages.getWarningIcon())
            .doNotAsk(option)
            .show(project)
        return when (answer) {
            Messages.YES -> LinkedDeleteChoice(LinkedDeleteAnswer.MOVE, stopAsking)
            Messages.NO -> LinkedDeleteChoice(LinkedDeleteAnswer.CLOSE, stopAsking)
            else -> LinkedDeleteChoice(LinkedDeleteAnswer.CANCEL)
        }
    }

    /** LIFE-3b: warning dialog with Delete / Cancel and a "Don't ask again" box that only counts together with Delete. */
    override fun confirmDelete(groupName: String, tabCount: Int): DeleteAnswer {
        var stopAsking = false
        val option = object : DoNotAskOption.Adapter() {
            override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                stopAsking = isSelected && exitCode == Messages.YES
            }
        }
        val confirmed = MessageDialogBuilder
            .yesNo(
                BetterEditorTabsBundle.message("delete.title"),
                BetterEditorTabsBundle.message("delete.message", groupName, tabCount),
            )
            .yesText(BetterEditorTabsBundle.message("delete.yes"))
            .noText(BetterEditorTabsBundle.message("delete.no"))
            .icon(Messages.getWarningIcon())
            .doNotAsk(option)
            .ask(project)
        return when {
            !confirmed -> DeleteAnswer.CANCEL
            stopAsking -> DeleteAnswer.DELETE_AND_STOP_ASKING
            else -> DeleteAnswer.DELETE
        }
    }
}
