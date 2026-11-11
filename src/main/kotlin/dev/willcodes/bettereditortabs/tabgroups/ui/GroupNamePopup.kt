package dev.willcodes.bettereditortabs.tabgroups.ui

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Condition
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * PLACEHOLDER for the group popup (§6): only the name field for now. Stage 3 replaces it with the full popup (swatches, Ungroup /
 * Close / Delete, Advanced).
 *
 * One text field, prefilled with the group's name and focused. Every edit renames the group at once (live), Enter, Escape or a click
 * outside closes the popup (POP-1); there is nothing to commit or revert. Shown below the group's header.
 */
object GroupNamePopup {
    /** INT-4: the popup for the group of [header] (a right click on it), below the header. */
    fun show(project: Project, header: HeaderRef) {
        val popup = create(project, header.groupId) ?: return
        popup.showUnderneathOf(header.component)
    }

    /**
     * CTX-1: the popup for the group [groupId] that was just created in [split]. The header is placed by a layout pass, which
     * [TabBar.header] runs; if it is still not showing, one more try follows in `invokeLater`, then the best position for [dataContext]
     * (or the middle of the window without one).
     */
    fun showForNewGroup(project: Project, split: SplitHandle, groupId: String, dataContext: DataContext?) {
        val popup = create(project, groupId) ?: return
        val tabBar = project.service<TabBar>()
        tabBar.header(split, groupId)?.let {
            popup.showUnderneathOf(it.component)
            return
        }
        ApplicationManager.getApplication().invokeLater(
            {
                val header = tabBar.header(split, groupId)
                when {
                    header != null -> popup.showUnderneathOf(header.component)
                    dataContext != null -> popup.showInBestPositionFor(dataContext)
                    else -> popup.showCenteredInCurrentWindow(project)
                }
            },
            Condition<Any?> { project.isDisposed },
        )
    }

    private fun create(project: Project, groupId: String): JBPopup? {
        val service = project.service<TabGroupsService>()
        val group = service.group(groupId) ?: return null
        val field = JBTextField(group.name, FIELD_COLUMNS)
        field.emptyText.text = BetterEditorTabsBundle.message("popup.name.placeholder")
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = service.rename(groupId, field.text)
        })
        val content: JComponent = JBUI.Panels.simplePanel(field).withBorder(JBUI.Borders.empty(6))
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, field)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(false)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        field.addActionListener { popup.closeOk(null) } // Enter
        field.selectAll()
        return popup
    }

    private const val FIELD_COLUMNS = 20
}
