package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.model.groups.TabGroup
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.Border
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent

/** What the popup content asks its owner to do; the owner closes the popup first and runs the action afterwards. */
internal class GroupPopupActions(
    val close: () -> Unit,
    val ungroup: () -> Unit,
    val stashGroup: () -> Unit,
    val delete: () -> Unit,
    val repack: () -> Unit,
)

/**
 * The content of the group popup (§6), top to bottom: the name field and the colour swatches, the actions (Ungroup, Stash Group,
 * Delete Group), the Advanced section; native popup separators between the blocks. Name and colour changes are applied to
 * [service] at once (POP-1: nothing to commit). Advanced (Stage 11) is present but disabled.
 *
 * Layout: the swatch row defines the width, the field stretches to it. The field, the first swatch and the row texts start at the same
 * x (the selection inset plus the inner inset of a list popup). Up / Down walk through field, swatches and the enabled rows
 * ([PopupFocusRing]); Tab does the same through Swing's focus traversal.
 */
internal class GroupPopupContent(group: TabGroup, service: TabGroupsService, actions: GroupPopupActions) {
    val nameField: JBTextField = JBTextField(1)
    val component: JComponent

    init {
        val groupId = group.id
        nameField.text = group.name
        nameField.emptyText.text = BetterEditorTabsBundle.message("popup.name.placeholder")
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = service.rename(groupId, nameField.text)
        })
        nameField.addActionListener { actions.close() } // Enter
        nameField.selectAll()

        val swatches = ColorSwatches(group.color) { service.recolor(groupId, it) }
        val ungroup = PopupMenuRow(BetterEditorTabsBundle.message("popup.action.ungroup"), true, actions.ungroup)
        val stashGroup = PopupMenuRow(BetterEditorTabsBundle.message("popup.action.stashGroup"), true, actions.stashGroup)
        val delete = PopupMenuRow(BetterEditorTabsBundle.message("popup.action.deleteGroup"), true, actions.delete)
        val advanced = AdvancedSection(false, actions.repack)
        // what each action does with the tabs, and whether it can be undone
        ungroup.toolTipText = BetterEditorTabsBundle.message("popup.action.ungroup.tooltip")
        stashGroup.toolTipText = BetterEditorTabsBundle.message("popup.action.stashGroup.tooltip")
        delete.toolTipText = BetterEditorTabsBundle.message("popup.action.deleteGroup.tooltip")

        val ring = PopupFocusRing()
        listOf(nameField, swatches, ungroup, stashGroup, delete, advanced.focusTarget).forEach(ring::register)

        val textInset = textInset()
        val swatchInset = textInset - (JBUI.scale(ColorSwatches.CELL) - JBUI.scale(ColorSwatches.CIRCLE)) / 2
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.background = JBUI.CurrentTheme.Popup.BACKGROUND
        root.border = JBUI.Borders.empty(
            JBUI.CurrentTheme.Popup.bodyTopInsetNoHeader(), 0, JBUI.CurrentTheme.Popup.bodyBottomInsetNoAd(), 0,
        )
        root.add(wrap(nameField, emptyBorder(0, textInset, JBUI.scale(FIELD_GAP), textInset)))
        root.add(wrap(swatches, emptyBorder(0, swatchInset, 0, swatchInset)))
        root.add(PopupSeparator())
        root.add(ungroup.aligned())
        root.add(stashGroup.aligned())
        root.add(delete.aligned())
        root.add(PopupSeparator())
        root.add(advanced.component)
        component = root
    }

    private fun wrap(child: JComponent, border: Border): JComponent =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            this.border = border
            alignmentX = Component.LEFT_ALIGNMENT
            add(child, BorderLayout.CENTER)
        }

    /** Pixel insets that are already scaled (the popup selection insets come scaled from the platform). */
    private fun emptyBorder(top: Int, left: Int, bottom: Int, right: Int): Border =
        EmptyBorder(top, left, bottom, right)

    private fun JComponent.aligned(): JComponent = also { alignmentX = Component.LEFT_ALIGNMENT }

    private companion object {
        const val FIELD_GAP = 8

        /** Left edge of field, first swatch and row texts: the selection inset plus the inner inset of a list popup (scaled). */
        fun textInset(): Int =
            JBUI.CurrentTheme.Popup.Selection.LEFT_RIGHT_INSET.get() + JBUI.CurrentTheme.Popup.Selection.innerInsets().left
    }
}

/** The thin line between popup blocks, painted like a list popup's separator (`Popup.separatorColor`, `Popup.separatorInsets`). */
private class PopupSeparator : JComponent() {
    init {
        alignmentX = Component.LEFT_ALIGNMENT
        isOpaque = false
    }

    override fun getPreferredSize(): Dimension {
        val insets = JBUI.CurrentTheme.Popup.separatorInsets()
        return Dimension(0, insets.top + 1 + insets.bottom)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val insets = JBUI.CurrentTheme.Popup.separatorInsets()
        g.color = JBUI.CurrentTheme.Popup.separatorColor()
        g.fillRect(insets.left, insets.top, width - insets.left - insets.right, 1)
    }
}
