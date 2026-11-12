package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.icons.AllIcons
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import java.awt.Component
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The "Advanced" part of the group popup (§6): a disclosure row ("Advanced ▸": the text, then the chevron) and, below it, a [body] that is hidden until
 * the row is expanded. In Stage 3 the row is disabled and the body stays empty. Stage 11 adds the Auto-group checkbox, the rule type
 * combo and the rule fields to [body] and enables the row; the popup must re-pack when the row is toggled, so [onToggled] is called
 * after every change and the owner packs the popup to its new content size.
 */
internal class AdvancedSection(enabled: Boolean, private val onToggled: () -> Unit) {
    private val row = PopupMenuRow(BetterEditorTabsBundle.message("popup.advanced"), enabled) { toggle() }

    /** The content below the row, hidden until expanded; Stage 11 fills it. */
    val body: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        isVisible = false
    }

    /** The row followed by the body. */
    val component: JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        row.alignmentX = Component.LEFT_ALIGNMENT
        add(row)
        add(body)
    }

    /** The focusable row, for keyboard navigation. */
    val focusTarget: JComponent get() = row

    var expanded: Boolean = false
        private set

    init {
        showChevron()
    }

    private fun toggle() {
        expanded = !expanded
        body.isVisible = expanded
        showChevron()
        onToggled()
    }

    private fun showChevron() {
        row.trailingIcon = if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
    }
}
