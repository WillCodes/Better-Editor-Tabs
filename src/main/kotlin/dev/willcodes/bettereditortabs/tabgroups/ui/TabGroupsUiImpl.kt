package dev.willcodes.bettereditortabs.tabgroups.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.service.DeleteAnswer
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsUi
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef
import dev.willcodes.bettereditortabs.tabgroups.ui.popup.GroupPopup

/** The real popups and dialogs behind [TabGroupsUi]; registered as the project service for that interface in `plugin.xml`. */
class TabGroupsUiImpl(private val project: Project) : TabGroupsUi {
    override fun showGroupPopup(header: HeaderRef) = GroupPopup.show(project, header)

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
