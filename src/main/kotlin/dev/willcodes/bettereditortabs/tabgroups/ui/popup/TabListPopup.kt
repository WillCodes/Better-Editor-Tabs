package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Condition
import com.intellij.openapi.util.Iconable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.IconUtil
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef
import java.awt.Component
import java.awt.Container
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The hover list of a collapsed group: its open tabs (file icon and tab title), in a popup beside the header (on the same side as the group popup), shown after
 * [SHOW_DELAY_MS]. One controller per project, EDT only. The popup never takes focus from the editor. Picking an entry closes it and
 * runs [TabGroupsService.openFromTabList]. It closes when the pointer leaves the header and does not reach the list within [GRACE_MS],
 * or later leaves the list; Escape and a click outside close it too. See [PopupPlacement] for where it opens.
 */
internal class TabListPopup(private val project: Project) {
    private var header: HeaderRef? = null
    private var files: List<VirtualFile> = emptyList()
    private var popup: JBPopup? = null

    private val showTimer = Timer(SHOW_DELAY_MS) { showNow() }.apply { isRepeats = false }
    private val closeTimer = Timer(GRACE_MS) { closeIfPointerAway() }.apply { isRepeats = false }

    /** The pointer rests on [header]: schedules the list. Repeated for the same header while pending or shown: no change. */
    fun hoverStarted(header: HeaderRef, files: List<VirtualFile>) {
        if (sameHeader(header)) {
            closeTimer.stop()
            return
        }
        cancel()
        this.header = header
        this.files = files
        showTimer.restart()
    }

    /** The pointer left (or pressed) [header]: drops a pending list; a shown one stays for [GRACE_MS] so the pointer can reach it. */
    fun hoverEnded(header: HeaderRef) {
        if (!sameHeader(header)) return
        if (popup == null) cancel() else closeTimer.restart()
    }

    private fun sameHeader(other: HeaderRef): Boolean = header?.let { it.split == other.split && it.groupId == other.groupId } == true

    /** Drops everything: a pending list, and closes a shown one (the group popup is about to open). */
    fun cancel() {
        val shown = popup
        forget()
        shown?.cancel()
    }

    private fun forget() {
        showTimer.stop()
        closeTimer.stop()
        popup = null
        header = null
        files = emptyList()
    }

    private fun showNow() {
        val header = header ?: return
        if (popup != null || project.isDisposed) return
        // Not for a pill that scrolled away, not on top of another popup (the group popup, a menu), and not while the IDE window is
        // inactive: there the first click only activates the window and the platform closes the list before the click picks an entry
        // (a platform quirk, also with `setCancelOnWindowDeactivation(false)`), so a list that cannot be used is not offered.
        val window = SwingUtilities.getWindowAncestor(header.component)
        if (!header.component.isShowing || JBPopupFactory.getInstance().isPopupActive || window?.isActive != true) {
            forget()
            return
        }
        val created = create(header)
        popup = created
        created.show(PopupPlacement.beside(header, created))
        trackPointer(created)
    }

    private fun create(header: HeaderRef): JBPopup {
        val service = project.service<TabGroupsService>()
        var self: JBPopup? = null
        val created = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(files)
            .setRenderer(renderer(project))
            .setAutoselectOnMouseMove(true)
            .setRequestFocus(false)
            .setMovable(false)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            .setItemChosenCallback { file ->
                self?.closeOk(null)
                // after the popup is gone, so the expand animation and the focus change do not race its disposal
                ApplicationManager.getApplication().invokeLater(
                    { service.openFromTabList(header.split, header.groupId, file) },
                    Condition<Any?> { project.isDisposed },
                )
            }
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (popup === self) forget()
                }
            })
            .createPopup()
        self = created
        return created
    }

    /** Follows the pointer over the popup's components: entering keeps it open, leaving starts the grace period. */
    private fun trackPointer(popup: JBPopup) {
        val tracker = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = closeTimer.stop()

            override fun mouseExited(e: MouseEvent) = closeTimer.restart()
        }
        fun attach(c: Component) {
            c.addMouseListener(tracker)
            (c as? Container)?.components?.forEach { attach(it) }
        }
        attach(popup.content)
    }

    /** The grace period ended: closes the list unless the pointer is on the popup (coming back to the header restarts the hover instead). */
    private fun closeIfPointerAway() {
        val shown = popup ?: return
        val pointer = MouseInfo.getPointerInfo()?.location
        val over = pointer != null && (screenBounds(shown.content)?.contains(pointer) == true)
        if (!over) cancel()
    }

    private fun screenBounds(c: Component?): Rectangle? = if (c != null && c.isShowing) Rectangle(c.locationOnScreen, c.size) else null

    companion object {
        /** How long the pointer must rest on the header before the list opens. */
        const val SHOW_DELAY_MS = 500

        /** Time for the pointer to cross the few pixels between the header and the list without closing it. */
        const val GRACE_MS = 200

        /**
         * One row: the file's icon and the text its editor tab shows (`EditorTabPresentationUtil.getEditorTabTitle`: the tab title
         * providers, including the "unique name" one that adds a folder for same-named files; not the tab's tint or pin state).
         */
        fun renderer(project: Project): SimpleListCellRenderer<VirtualFile> = object : SimpleListCellRenderer<VirtualFile>() {
            override fun customize(list: JList<out VirtualFile>, value: VirtualFile?, index: Int, selected: Boolean, hasFocus: Boolean) {
                val file = value ?: return
                text = EditorTabPresentationUtil.getEditorTabTitle(project, file)
                icon = IconUtil.getIcon(file, Iconable.ICON_FLAG_READ_STATUS, project)
            }
        }
    }
}
