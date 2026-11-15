package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DocumentAdapter
import dev.willcodes.bettereditortabs.tabgroups.model.groups.AutoGroupRule
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.model.groups.TabGroup
import dev.willcodes.bettereditortabs.tabgroups.service.RuleFiles
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
    /** Split Right / Split Down: a new split with a linked copy of the group. */
    val splitRight: () -> Unit = {},
    val splitDown: () -> Unit = {},
    /** False for a group without an open tab in this split (a split needs a tab): the split rows are disabled. */
    val canSplit: Boolean = true,
)

/**
 * The content of the group popup, top to bottom: the name field and the colour swatches, the actions (Ungroup, Stash Group,
 * Delete Group), the Advanced section; native popup separators between the blocks. Name and colour changes are applied to
 * [service] at once (nothing to commit). The auto-group rule is the exception: it is edited in [rules] and committed
 * by the owner when the popup closes, from [editedRule].
 *
 * Layout: the swatch row defines the width, the field stretches to it (the rule fields too: they have the swatch row's width, not a
 * width of their own). The field, the first swatch and the row texts start at the same x (the selection inset plus the inner inset of
 * a list popup). Up / Down walk through field, swatches, the enabled rows and the enabled rule controls ([PopupFocusRing]); Tab does
 * the same through Swing's focus traversal.
 *
 * Advanced opens as the user left it in this IDE session, or (before any toggle) expanded for a group with a rule.
 * [resolveDirectory] finds the directory a path names (tests replace it; see [RuleEditor]).
 */
internal class GroupPopupContent(
    project: Project,
    group: TabGroup,
    service: TabGroupsService,
    actions: GroupPopupActions,
    resolveDirectory: (String) -> VirtualFile? = { path -> RuleEditor.resolveOnDisk(path) },
) {
    val nameField: JBTextField = JBTextField(1)
    val component: JComponent

    /** The rule editor in the Advanced section. */
    val rules: RuleEditor

    /** The Advanced section (disclosure row and body). */
    val advanced: AdvancedSection

    /** True while a modal child window (the folder chooser) is open: closing the popup then would take the chooser's owner away. */
    val childWindowOpen: Boolean get() = rules.chooserOpen

    /** The rule to commit when the popup closes; see [RuleEditor.edited]. */
    fun editedRule(): AutoGroupRule? = rules.edited()

    private val previewGroupId = group.id
    private val previewService = service

    /**
     * Shows which open tabs (and Project view files) the rule being edited matches, while Advanced is expanded; clears the
     * preview otherwise. Called when the popup opens, on every rule edit and when Advanced is toggled; the owner clears it on close.
     */
    fun previewNow() {
        previewService.previewRule(previewGroupId, if (advanced.expanded) rules.previewRule() else null)
    }

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
        val splitRight = PopupMenuRow(BetterEditorTabsBundle.message("popup.action.splitRight"), actions.canSplit, onActivate = actions.splitRight)
        val splitDown = PopupMenuRow(BetterEditorTabsBundle.message("popup.action.splitDown"), actions.canSplit, onActivate = actions.splitDown)
        splitRight.toolTipText = BetterEditorTabsBundle.message("popup.action.splitRight.tooltip")
        splitDown.toolTipText = BetterEditorTabsBundle.message("popup.action.splitDown.tooltip")
        val ungroup = PopupMenuRow(BetterEditorTabsBundle.message("popup.action.ungroup"), true, onActivate = actions.ungroup)
        val stashGroup = PopupMenuRow(BetterEditorTabsBundle.message("popup.action.stashGroup"), true, onActivate = actions.stashGroup)
        val delete = PopupMenuRow(BetterEditorTabsBundle.message("popup.action.deleteGroup"), true, danger = true, onActivate = actions.delete)
        advanced = AdvancedSection(true, AdvancedSection.startExpanded(group.rule != null)) {
            previewNow()
            actions.repack()
        }
        // what each action does with the tabs, and whether it can be undone
        ungroup.toolTipText = BetterEditorTabsBundle.message("popup.action.ungroup.tooltip")
        stashGroup.toolTipText = BetterEditorTabsBundle.message("popup.action.stashGroup.tooltip")
        delete.toolTipText = BetterEditorTabsBundle.message("popup.action.deleteGroup.tooltip")

        val textInset = textInset()
        val swatchInset = textInset - (JBUI.scale(ColorSwatches.CELL) - JBUI.scale(ColorSwatches.CIRCLE)) / 2
        // the swatch row defines the popup's width; the rule fields get what is left of it beside the text insets
        val fieldWidth = swatches.preferredSize.width + 2 * swatchInset - 2 * textInset
        rules = RuleEditor(
            project, group.rule, textInset, fieldWidth, actions.repack, actions.close, resolveDirectory,
            onRuleChanged = { previewNow() },
            matchingTabs = { rule ->
                service.previewMatches(rule).values.flatten().distinct().map { file ->
                    MatchHint.Entry(file.name, RuleFiles.of(project, file).let { it.projectRelativePath ?: it.absolutePath })
                }
            },
        )
        advanced.body.add(rules.component)
        advanced.prepareLayout()

        val ring = PopupFocusRing()
        listOf(nameField, swatches, splitRight, splitDown, ungroup, stashGroup, delete, advanced.focusTarget, rules.autoGroupBox, rules.directoryField.textField, rules.regexField)
            .forEach(ring::register)
        ring.registerCombo(rules.kindCombo)
        ring.registerCombo(rules.moduleCombo)
        val root = JPanel()
        root.layout = BoxLayout(root, BoxLayout.Y_AXIS)
        root.background = JBUI.CurrentTheme.Popup.BACKGROUND
        root.border = JBUI.Borders.empty(
            JBUI.CurrentTheme.Popup.bodyTopInsetNoHeader(), 0, JBUI.CurrentTheme.Popup.bodyBottomInsetNoAd(), 0,
        )
        root.add(wrap(nameField, emptyBorder(0, textInset, JBUI.scale(FIELD_GAP), textInset)))
        root.add(wrap(swatches, emptyBorder(0, swatchInset, 0, swatchInset)))
        root.add(PopupSeparator())
        root.add(splitRight.aligned())
        root.add(splitDown.aligned())
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
            background = JBUI.CurrentTheme.Popup.BACKGROUND // Islands: rounded fields fill their corners with the parent's background
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
