package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Condition
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.service.GroupColors
import dev.willcodes.bettereditortabs.tabgroups.service.StashedGroup
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel

/**
 * The Restore Tab Group… popup (LIFE-4): the project's stash, newest first, one row per entry (colour dot, name, "3 tabs"). Enter or a
 * click restores the entry into the split (after the popup has closed), Delete removes it from the stash; speed search finds a group by
 * name. See [RestoreGroupContent] for the list itself.
 */
object RestoreGroupPopup {
    /** Shows the stash for [split] (null: the current split) next to [dataContext]'s component. Does nothing for an empty stash. */
    fun show(project: Project, split: SplitHandle?, dataContext: DataContext) {
        val service = project.service<TabGroupsService>()
        val entries = service.stashedGroups()
        if (entries.isEmpty()) return
        var popup: JBPopup? = null
        val content = RestoreGroupContent(
            entries,
            onRestore = { entryId ->
                popup?.closeOk(null)
                // after the popup is gone, so the restored tabs' focus change does not race its disposal
                ApplicationManager.getApplication().invokeLater(
                    { service.restoreStashed(split, entryId) },
                    Condition<Any?> { project.isDisposed },
                )
            },
            onDelete = { service.removeStashed(it) },
            onEmpty = { popup?.cancel() },
        )
        val created = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content.component, content.list)
            .setTitle(BetterEditorTabsBundle.message("restore.popup.title"))
            // the Delete key cannot be undone: say so where the list is
            .setAdText(BetterEditorTabsBundle.message("restore.popup.hint"))
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(false)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .createPopup()
        popup = created
        created.showInBestPositionFor(dataContext)
    }
}

/** The colour dot of a row: a filled circle in [color]. */
internal class ColorDotIcon(val color: Color) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(SIZE)

    override fun getIconHeight(): Int = JBUI.scale(SIZE)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fillOval(x, y, iconWidth, iconHeight)
        }
        finally {
            g2.dispose()
        }
    }

    private companion object {
        const val SIZE = 10
    }
}

/**
 * The list of the Restore Tab Group popup, independent of the service: [entries] in the given order, callbacks for the user's choices.
 * Enter and a click call [onRestore] with the entry id; Delete calls [onDelete] and drops the row at once, selecting the entry that
 * takes its place (the next one, or the previous one for the last row); [onEmpty] runs when the last entry is gone.
 */
internal class RestoreGroupContent(
    entries: List<StashedGroup>,
    private val onRestore: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onEmpty: () -> Unit,
) {
    private val model = CollectionListModel(entries)
    val list: JBList<StashedGroup> = JBList(model)
    val component: JComponent
    val renderer = renderer()

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = renderer
        list.visibleRowCount = minOf(entries.size.coerceAtLeast(1), MAX_ROWS)
        if (entries.isNotEmpty()) list.selectedIndex = 0
        ListSpeedSearch.installOn(list) { it.displayName }

        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_RESTORE)
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), ACTION_DELETE)
        list.actionMap.put(ACTION_RESTORE, action { restoreSelected() })
        list.actionMap.put(ACTION_DELETE, action { deleteSelected() })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.isPopupTrigger) return
                val index = list.locationToIndex(e.point)
                if (index < 0 || list.getCellBounds(index, index)?.contains(e.point) != true) return
                list.selectedIndex = index
                restoreSelected()
            }
        })
        component = JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }
    }

    /** Enter: restores the selected entry. */
    fun restoreSelected() {
        list.selectedValue?.let { onRestore(it.entryId) }
    }

    /** Delete: removes the selected entry from the list and the stash and selects its neighbour; the last one also runs [onEmpty]. */
    fun deleteSelected() {
        val index = list.selectedIndex
        val entry = list.selectedValue ?: return
        model.remove(index)
        onDelete(entry.entryId)
        if (model.isEmpty) {
            onEmpty()
        }
        else {
            list.selectedIndex = minOf(index, model.size - 1)
            list.ensureIndexIsVisible(list.selectedIndex)
            list.visibleRowCount = minOf(model.size, MAX_ROWS)
        }
    }

    private fun action(body: () -> Unit) = object : AbstractAction() {
        override fun actionPerformed(e: ActionEvent?) = body()
    }

    private fun renderer(): ColoredListCellRenderer<StashedGroup> = object : ColoredListCellRenderer<StashedGroup>() {
        override fun customizeCellRenderer(list: JList<out StashedGroup>, value: StashedGroup?, index: Int, selected: Boolean, hasFocus: Boolean) {
            val entry = value ?: return
            icon = ColorDotIcon(GroupColors.jbColor(entry.color))
            append(entry.displayName)
            append("  " + countText(entry.fileCount), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    companion object {
        const val MAX_ROWS = 12
        private const val ACTION_RESTORE = "BetterEditorTabs.restoreStashed"
        private const val ACTION_DELETE = "BetterEditorTabs.deleteStashed"

        /** "3 tabs" / "1 tab". */
        fun countText(fileCount: Int): String = BetterEditorTabsBundle.message("restore.tabCount", fileCount)
    }
}
