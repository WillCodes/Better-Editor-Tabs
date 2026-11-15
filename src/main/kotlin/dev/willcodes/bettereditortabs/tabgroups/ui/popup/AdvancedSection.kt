package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.icons.AllIcons
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The "Advanced" part of the group popup: a disclosure row ("Advanced ▸": the text, then the chevron) and, below it, a [body] that is hidden until
 * the row is expanded. The body holds the auto-group rule editor ([RuleEditor]); the popup must re-pack when the row is
 * toggled, so [onToggled] is called after every change and the owner packs the popup to its new content size.
 *
 * [startExpanded] is the state the popup opens in: what the user left last in this IDE session
 * ([sessionExpanded], once they have toggled it), else expanded for a group that has a rule. Every toggle is remembered.
 *
 * The body is laid out once while it is still hidden ([prepareLayout]), so the first expansion does not show a frame of components that
 * have never been laid out, which would otherwise glitch.
 */
internal class AdvancedSection(enabled: Boolean, startExpanded: Boolean, private val onToggled: () -> Unit) {
    private val row = PopupMenuRow(BetterEditorTabsBundle.message("popup.advanced"), enabled) { toggle() }

    /** The content below the row, hidden until expanded. */
    val body: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        background = com.intellij.util.ui.JBUI.CurrentTheme.Popup.BACKGROUND
        alignmentX = Component.LEFT_ALIGNMENT
        isVisible = startExpanded
    }

    /** The row followed by the body. */
    val component: JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        background = com.intellij.util.ui.JBUI.CurrentTheme.Popup.BACKGROUND
        alignmentX = Component.LEFT_ALIGNMENT
        row.alignmentX = Component.LEFT_ALIGNMENT
        add(row)
        add(body)
    }

    /** The focusable row, for keyboard navigation. */
    val focusTarget: JComponent get() = row

    var expanded: Boolean = startExpanded
        private set

    init {
        showChevron()
    }

    /** Lays the body out at its preferred size while hidden (see the class comment); call once its content has been added. */
    fun prepareLayout() {
        val size = body.preferredSize
        body.setSize(size)
        layoutTree(body)
    }

    private fun layoutTree(c: java.awt.Container) {
        c.doLayout()
        for (child in c.components) if (child is java.awt.Container) layoutTree(child)
    }

    private fun toggle() {
        expanded = !expanded
        sessionExpanded = expanded
        body.isVisible = expanded
        showChevron()
        onToggled()
    }

    private fun showChevron() {
        row.trailingIcon = if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
    }

    companion object {
        /**
         * Whether the user left Advanced expanded, null until they have toggled it in this IDE session (a static, so it is shared
         * by all popups and projects).
         */
        @Volatile
        var sessionExpanded: Boolean? = null
            internal set

        /** The state a popup opens in: the remembered one, else expanded for a group with a rule. */
        fun startExpanded(hasRule: Boolean): Boolean = sessionExpanded ?: hasRule
    }
}
