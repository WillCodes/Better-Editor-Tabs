package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.impl.EditorEmptyTextPainter
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.ui.tabs.impl.JBEditorTabs
import java.awt.Graphics
import java.awt.Rectangle
import javax.swing.JComponent

/**
 * The empty-editor state of a split whose tabs are all hidden by collapsed groups.
 *
 * The platform's rich empty state is driven by `EditorsSplitters.showEmptyText()` (`currentWindow.files().none()`), which counts hidden
 * tabs, so it cannot be triggered. This paints the platform's fallback empty text (`EditorEmptyTextPainter`) over the content area
 * instead, and the strip background over the strip. A child of the tab component, laid out by [update] after every layout pass, last in
 * z-order and invisible to the mouse.
 *
 * Internal APIs wrapped: `EditorEmptyTextPainter` (internal), `EditorWindow.owner`, `JBTabsImpl.tabPainter`. EDT only.
 */
internal class EmptyEditorOverlay(private val tabs: JBEditorTabs, private val window: EditorWindow) : JComponent() {
    private var strip: Rectangle = Rectangle()

    init {
        isOpaque = false
        // JBTabsImpl.LAYOUT_DONE: components with this client property are not reset to 0x0 by the platform's layout.
        putClientProperty("Layout.done", true)
    }

    override fun contains(x: Int, y: Int): Boolean = false

    /** Shows the overlay over the whole tab component if [show] (else hides it); [stripRect] is the strip area to paint as strip. */
    fun update(show: Boolean, stripRect: Rectangle) {
        val overlayBounds = if (show) Rectangle(0, 0, tabs.width, tabs.height) else Rectangle()
        strip = stripRect
        if (bounds != overlayBounds) bounds = overlayBounds
        val last = tabs.componentCount - 1
        if (tabs.getComponentZOrder(this) != last) tabs.setComponentZOrder(this, last)
    }

    override fun paintComponent(g: Graphics) {
        if (width <= 0 || height <= 0) return
        g.color = EditorColorsManager.getInstance().globalScheme.defaultBackground
        g.fillRect(0, 0, width, height)
        g.color = tabs.tabPainter.getBackgroundColor()
        g.fillRect(strip.x, strip.y, strip.width, strip.height)
        val painter = ApplicationManager.getApplication().getService(EditorEmptyTextPainter::class.java)
        painter.paintEmptyText(window.owner, this, g)
    }
}
