package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Condition
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteTrigger
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar

/**
 * The group popup (§6): the only place a group is renamed, recoloured, ungrouped or deleted from. Opens next to its header (below it for a strip on top; see
 * [PopupPlacement]) with the name field focused, all text selected. Closes on Enter, Escape or a click outside (POP-1); everything is
 * applied while editing, so closing commits nothing more. See [GroupPopupContent] for the layout and the keyboard navigation.
 */
object GroupPopup {
    /** INT-4: the popup for the group of [header] (a right click on it), next to the header. */
    fun show(project: Project, header: HeaderRef) {
        val popup = create(project, header.split, header.groupId) ?: return
        popup.show(PopupPlacement.beside(header, popup))
    }

    /**
     * CTX-1: the popup for the group [groupId] that was just created in [split]. The header is placed by a layout pass, which
     * [TabBar.header] runs; if it is still not showing, one more try follows in `invokeLater`, then the best position for [dataContext]
     * (or the middle of the window without one).
     */
    fun showForNewGroup(project: Project, split: SplitHandle, groupId: String, dataContext: DataContext?) {
        val popup = create(project, split, groupId) ?: return
        val tabBar = project.service<TabBar>()
        tabBar.header(split, groupId)?.let {
            popup.show(PopupPlacement.beside(it, popup))
            return
        }
        ApplicationManager.getApplication().invokeLater(
            {
                val header = tabBar.header(split, groupId)
                when {
                    header != null -> popup.show(PopupPlacement.beside(header, popup))
                    dataContext != null -> popup.showInBestPositionFor(dataContext)
                    else -> popup.showCenteredInCurrentWindow(project)
                }
            },
            Condition<Any?> { project.isDisposed },
        )
    }

    private fun create(project: Project, split: SplitHandle, groupId: String): JBPopup? {
        val service = project.service<TabGroupsService>()
        val group = service.group(groupId) ?: return null
        var popup: JBPopup? = null

        // The popup closes first, then the action runs, so a confirmation dialog never opens on top of a closing popup.
        fun closeThen(action: () -> Unit) {
            popup?.closeOk(null)
            ApplicationManager.getApplication().invokeLater(action, Condition<Any?> { project.isDisposed })
        }

        val content = GroupPopupContent(
            group,
            service,
            GroupPopupActions(
                close = { popup?.closeOk(null) },
                ungroup = { closeThen { service.ungroup(split, groupId) } },
                delete = { closeThen { service.requestDelete(split, groupId, DeleteTrigger.MENU) } },
                repack = { popup?.pack(true, true) },
            ),
        )
        val created = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content.component, content.nameField)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(false)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) = onClosed()
            })
            .createPopup()
        popup = created
        return created
    }

    /**
     * Runs once when the popup has closed, however it closed. POP-1 needs nothing here: name and colour are applied live, there is
     * no Save. Stage 11 runs the POP-2 prompt (offer to fill an auto-group from the open tabs) here.
     */
    private fun onClosed() {}
}
