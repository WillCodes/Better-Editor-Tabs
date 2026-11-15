package dev.willcodes.bettereditortabs.tabgroups.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.service.DeleteAnswer
import dev.willcodes.bettereditortabs.tabgroups.actions.NewEmptyGroupAction
import dev.willcodes.bettereditortabs.tabgroups.service.EmptyCase
import dev.willcodes.bettereditortabs.tabgroups.service.EmptyGroupAnswer
import dev.willcodes.bettereditortabs.tabgroups.service.EmptyGroupChoice
import dev.willcodes.bettereditortabs.tabgroups.service.LinkedDeleteAnswer
import dev.willcodes.bettereditortabs.tabgroups.service.LinkedDeleteChoice
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsUi
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import java.awt.Point
import javax.swing.JComponent
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

    /** Modal warning naming the skipped files (at most [MAX_LISTED]), or saying that nothing could be restored. */
    override fun warnMissingFiles(groupName: String, missing: List<String>, restored: Int) {
        val message = if (restored <= 0) {
            BetterEditorTabsBundle.message("restore.missing.none", groupName)
        }
        else {
            val shown = missing.take(MAX_LISTED).toMutableList()
            if (missing.size > MAX_LISTED) shown += BetterEditorTabsBundle.message("restore.missing.more", missing.size - MAX_LISTED)
            BetterEditorTabsBundle.message("restore.missing.partial", groupName, missing.size, shown.joinToString("\n"))
        }
        Messages.showWarningDialog(project, message, BetterEditorTabsBundle.message("restore.missing.title"))
    }

    /**
     * Three buttons, Merge Tabs (the default: Move), Close Tabs, Cancel, and a "Don't ask again" box that only counts with Move or Close. `MessageDialogBuilder.yesNoCancel` maps them to yes, no and cancel;
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

    /** Warning dialog with Delete / Cancel and a "Don't ask again" box that only counts together with Delete. */
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

    /**
     * "N open tabs match this rule. Move them into "‹name›"?" with Move / Leave; true for Move. Like [confirmDelete] a
     * `MessageDialogBuilder.yesNo` dialog, with the question icon (nothing is lost whichever way it is answered) and no "don't
     * ask again" box: the prompt is switched off in the settings.
     */
    override fun askMoveMatching(groupName: String, count: Int): Boolean =
        MessageDialogBuilder
            .yesNo(
                BetterEditorTabsBundle.message("rule.move.title"),
                BetterEditorTabsBundle.message("rule.move.message", count, groupName),
            )
            .yesText(BetterEditorTabsBundle.message("rule.move.yes"))
            .noText(BetterEditorTabsBundle.message("rule.move.no"))
            .icon(Messages.getQuestionIcon())
            .ask(project)

    /**
     * *"‹name›" is empty. Delete it?* for a group without a rule, *"‹name›" has no tabs left. Keep it so that new
     * matching files join it?* for one with a rule, each worded for the [case] (closed, dragged out, popup closed). Buttons Delete / Keep
     * (Delete is the dialog's yes, so Escape and the close button answer Keep, the safe one) and a "Don't ask again" box that counts
     * with either answer.
     */
    override fun askDeleteEmpty(groupName: String, ruled: Boolean, case: EmptyCase): EmptyGroupChoice {
        var stopAsking = false
        val option = object : DoNotAskOption.Adapter() {
            override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
                stopAsking = isSelected
            }
        }
        val key = (if (ruled) "empty.ruled.message." else "empty.message.") + case.name.lowercase()
        val delete = MessageDialogBuilder
            .yesNo(BetterEditorTabsBundle.message("empty.title"), BetterEditorTabsBundle.message(key, groupName))
            .yesText(BetterEditorTabsBundle.message("empty.delete"))
            .noText(BetterEditorTabsBundle.message("empty.keep"))
            .icon(Messages.getQuestionIcon())
            .doNotAsk(option)
            .ask(project)
        return EmptyGroupChoice(if (delete) EmptyGroupAnswer.DELETE else EmptyGroupAnswer.KEEP, stopAsking)
    }

    /**
     * One warning for the empty groups of a temporary split that was closed: *"‹name›" had no tabs, so it was moved to the split
     * you used last.* and / or *"‹name›" had no tabs and no other split to go to, so it was deleted.* (several names are listed together).
     */
    override fun warnTemporarySplitClosed(moved: List<String>, deleted: List<String>) {
        val lines = ArrayList<String>()
        if (moved.isNotEmpty()) lines += BetterEditorTabsBundle.message("temporary.closed.moved." + (if (moved.size == 1) "one" else "many"), quoted(moved))
        if (deleted.isNotEmpty()) lines += BetterEditorTabsBundle.message("temporary.closed.deleted." + (if (deleted.size == 1) "one" else "many"), quoted(deleted))
        if (lines.isEmpty()) return
        Messages.showWarningDialog(project, lines.joinToString("\n\n"), BetterEditorTabsBundle.message("temporary.closed.title"))
    }

    private fun quoted(names: List<String>): String = names.joinToString(", ") { "\"$it\"" }

    /** A native context menu with *New Group* at [point] in [component]. */
    override fun showStripMenu(split: SplitHandle, component: JComponent, point: Point) {
        val newGroup = object : AnAction(BetterEditorTabsBundle.message("strip.newGroup")), DumbAware {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) {
                NewEmptyGroupAction.createAndName(project, split, e.dataContext)
            }
        }
        val menu = ActionManager.getInstance().createActionPopupMenu(STRIP_MENU_PLACE, DefaultActionGroup(newGroup))
        menu.setTargetComponent(component)
        menu.component.show(component, point.x, point.y)
    }

    private companion object {
        const val STRIP_MENU_PLACE = "BetterEditorTabs.StripPopup"
        const val MAX_LISTED = 10
    }
}
