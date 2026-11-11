package dev.willcodes.bettereditortabs.tabgroups.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService

/**
 * CTX-2: the Add Tab to Group submenu: every group of the project in creation order (colour swatch and name, "Unnamed group (Blue)" for
 * unnamed ones), a separator, then New Group. The items are bound to the tab that is right-clicked when the menu opens, so they do not
 * depend on the context menu still being up when one is picked. The whole submenu is disabled for pinned tabs (CTX-4).
 */
class AddTabToGroupActionGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val context = TabContext.of(e)
        e.presentation.isEnabled = context != null && !context.pinned
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        if (e == null) return EMPTY_ARRAY
        val context = TabContext.of(e) ?: return EMPTY_ARRAY
        val service = context.project.service<TabGroupsService>()
        val items = ArrayList<AnAction>()
        for (group in service.groups()) {
            val icon = ColorIcon(JBUI.scale(SWATCH_SIZE), service.colorOf(group))
            items += AddToGroupItem(context, group.id, service.displayName(group), icon)
        }
        if (items.isNotEmpty()) items += Separator.getInstance()
        items += NewGroupItem(context)
        return items.toTypedArray()
    }

    private class AddToGroupItem(
        private val context: TabContext,
        private val groupId: String,
        name: String,
        icon: javax.swing.Icon,
    ) : AnAction(), DumbAware {
        init {
            templatePresentation.setText(name, false) // a group name is not a mnemonic string
            templatePresentation.icon = icon
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            context.project.service<TabGroupsService>().addToGroup(context.split, context.file, groupId)
        }
    }

    private class NewGroupItem(private val context: TabContext) : AddTabToNewGroupAction() {
        init {
            templatePresentation.text = BetterEditorTabsBundle.message("action.addToGroup.newGroup")
        }

        override fun update(e: AnActionEvent) {}

        override fun actionPerformed(e: AnActionEvent) = createAndName(context, e)
    }

    private companion object {
        const val SWATCH_SIZE = 13
    }
}
