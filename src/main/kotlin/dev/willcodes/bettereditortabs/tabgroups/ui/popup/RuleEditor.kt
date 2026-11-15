package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.model.groups.AutoGroupRule
import dev.willcodes.bettereditortabs.tabgroups.model.groups.RuleKind
import dev.willcodes.bettereditortabs.tabgroups.model.groups.RuleMatching
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.util.regex.PatternSyntaxException
import javax.swing.JList
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent

/**
 * The body of the popup's Advanced section: the *Auto-group* checkbox, the rule type combo box and, below it, the
 * field of the selected type only (module combo, directory field with a browse button, or regex field). The combo and the fields are
 * disabled while *Auto-group* is unticked. An invalid regex or directory shows an inline error under the fields.
 *
 * [edited] is what the popup commits when it closes: see there. The editor keeps no state outside its components, so the
 * owner builds one per popup. [onResized] is called when the content's height changed (another field, an error line) so that the owner
 * re-packs the popup; [onEnter] when Enter was pressed in a text field (closes the popup like Enter in the name field).
 *
 * **Directory validity** (only directories inside the project can be chosen): a typed or chosen path counts if [resolveDirectory]
 * finds an existing directory for it (by default `LocalFileSystem`) and `ProjectFileIndex.isInContent` says it is under a content root
 * of the project (in a read action). The chooser is limited to the content roots, so the same rule holds for it. The rule stores the
 * directory's VFS URL; the field shows the system-dependent path.
 */
internal class RuleEditor(
    private val project: Project,
    private val previous: AutoGroupRule?,
    private val textInset: Int,
    fieldWidth: Int,
    private val onResized: () -> Unit,
    private val onEnter: () -> Unit,
    private val resolveDirectory: (String) -> VirtualFile? = { path -> resolveOnDisk(path) },
    /** The edited rule changed (any user edit); the owner refreshes the preview from [previewRule]. */
    private val onRuleChanged: () -> Unit = {},
    /** Match hint: the open tabs a rule would move (ungrouped, unpinned, not preview), in order, as name and matched path. */
    private val matchingTabs: (AutoGroupRule) -> List<MatchHint.Entry> = { emptyList() },
) {
    val autoGroupBox: JBCheckBox = JBCheckBox(BetterEditorTabsBundle.message("popup.rule.autoGroup"), previous?.enabled == true)
    val kindCombo: ComboBox<RuleKind> = ComboBox(DefaultComboBoxModel(RuleKind.entries.toTypedArray()))
    val moduleCombo: ComboBox<String> = ComboBox()
    val directoryField: TextFieldWithBrowseButton
    val regexField: JBTextField = JBTextField(1)
    /** The inline error (see [WrappingLabel]). */
    private val errorLabel = WrappingLabel { errorWidth }

    /** Under a valid regex, the first open tabs it would move ([MatchHint]); wraps at the popup's width like the error. */
    private val hintLabel = WrappingLabel { errorWidth }
    private var shownHint: String? = null

    private val moduleRow: JComponent
    private val directoryRow: JComponent
    private val regexRow: JComponent
    private val errorRow: JComponent
    private val hintRow: JComponent

    /** True while the popup is being filled, and for edits the code makes itself: they do not count as the user touching the rule. */
    private var loading = true
    private var touched = false
    private var shownError: String? = null
    private val errorWidth = fieldWidth

    /** Set while the folder chooser is open: the popup must not close then ([GroupPopup] reads it in its cancel callback). */
    var chooserOpen: Boolean = false
        private set

    /** The whole body; goes into the Advanced section's body panel. */
    val component: JComponent

    init {
        // the checkbox
        autoGroupBox.toolTipText = BetterEditorTabsBundle.message("popup.rule.autoGroup.tooltip")
        autoGroupBox.isOpaque = false
        autoGroupBox.addActionListener { userChanged() }

        // the rule type
        kindCombo.selectedItem = previous?.kind ?: RuleKind.MODULE
        kindCombo.renderer = object : SimpleListCellRenderer<RuleKind>() {
            override fun customize(list: JList<out RuleKind>, value: RuleKind?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.let { kindLabel(it) } ?: ""
            }
        }
        kindCombo.setMinimumAndPreferredWidth(fieldWidth)
        kindCombo.getAccessibleContext().accessibleName = BetterEditorTabsBundle.message("popup.rule.kind.accessibleName")
        kindCombo.addActionListener { userChanged() }

        // by module
        val names = moduleNames(previous?.takeIf { it.kind == RuleKind.MODULE }?.value)
        moduleCombo.model = DefaultComboBoxModel(names.toTypedArray())
        val previousModule = previous?.takeIf { it.kind == RuleKind.MODULE }?.value
        moduleCombo.selectedItem = previousModule ?: names.firstOrNull()
        moduleCombo.setMinimumAndPreferredWidth(fieldWidth)
        moduleCombo.getAccessibleContext().accessibleName = BetterEditorTabsBundle.message("popup.rule.module.accessibleName")
        moduleCombo.addActionListener { userChanged() }

        // by directory
        directoryField = TextFieldWithBrowseButton.NoPathCompletion { browse() }
        directoryField.textField.columns = 1
        directoryField.preferredSize = Dimension(fieldWidth, directoryField.preferredSize.height)
        directoryField.textField.getAccessibleContext().accessibleName = BetterEditorTabsBundle.message("popup.rule.directory.accessibleName")
        previous?.takeIf { it.kind == RuleKind.DIRECTORY }?.let { directoryField.text = displayPath(it.value) }
        directoryField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = userChanged()
        })
        directoryField.textField.addActionListener { onEnter() }

        // by path regex
        regexField.emptyText.text = BetterEditorTabsBundle.message("popup.rule.regex.placeholder")
        regexField.getAccessibleContext().accessibleName = BetterEditorTabsBundle.message("popup.rule.regex.accessibleName")
        previous?.takeIf { it.kind == RuleKind.PATH_REGEX }?.let { regexField.text = it.value }
        regexField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = userChanged()
        })
        regexField.addActionListener { onEnter() }

        // the inline error under the fields
        errorLabel.foreground = NamedColorUtil.getErrorForeground()
        errorLabel.font = JBUI.Fonts.smallFont()
        hintLabel.foreground = NamedColorUtil.getInactiveTextColor()
        hintLabel.font = JBUI.Fonts.smallFont()

        val gap = JBUI.scale(GAP)
        val top = JBUI.scale(TOP_GAP)
        moduleRow = row(moduleCombo, gap)
        directoryRow = row(directoryField, gap)
        regexRow = row(regexField, gap)
        errorRow = row(errorLabel, gap)
        hintRow = row(hintLabel, gap)
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        panel.background = JBUI.CurrentTheme.Popup.BACKGROUND
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(row(autoGroupBox, gap, top))
        panel.add(row(kindCombo, gap))
        panel.add(moduleRow)
        panel.add(directoryRow)
        panel.add(regexRow)
        panel.add(errorRow)
        panel.add(hintRow)
        component = panel

        refresh()
        loading = false
    }

    /** The controls that take the keyboard focus, top to bottom, for the popup's Up / Down ring (hidden ones are skipped there). */
    val focusables: List<JComponent> get() = listOf(autoGroupBox, kindCombo, moduleCombo, directoryField.textField, regexField)

    /** The kind whose field is showing. */
    val selectedKind: RuleKind get() = kindCombo.selectedItem as? RuleKind ?: RuleKind.MODULE

    /** The inline error showing under the fields, or null. */
    val errorText: String? get() = shownError

    /** Closes the module and kind drop-down lists if one is open (Escape closes the list first, not the popup); true if one was. */
    fun hideOpenList(): Boolean {
        val combo = listOf(kindCombo, moduleCombo).firstOrNull { it.isPopupVisible } ?: return false
        combo.hidePopup()
        return true
    }

    /**
     * What the popup commits when it closes:
     *  - not touched: exactly the previous rule;
     *  - *Auto-group* ticked and the selected type's value valid: `AutoGroupRule(true, kind, value)`;
     *  - ticked but the value empty or invalid: the previous rule, unchanged (an invalid rule is not saved);
     *  - unticked: `AutoGroupRule(false, kind, value)` if the value is valid (so that ticking again restores it), else the previous
     *    rule with `enabled = false` (null if there was none).
     */
    fun edited(): AutoGroupRule? {
        if (!touched) return previous
        val kind = selectedKind
        val value = currentValue(kind)
        return when {
            autoGroupBox.isSelected -> if (value != null) AutoGroupRule(true, kind, value) else previous
            value != null -> AutoGroupRule(false, kind, value)
            else -> previous?.copy(enabled = false)
        }
    }

    private fun userChanged() {
        if (loading) return
        touched = true
        refresh()
        onRuleChanged()
    }

    /** The match hint under a valid regex (HTML body), or null for an empty or invalid pattern. */
    private fun matchHint(): String? {
        val pattern = regexField.text
        val regex = RuleMatching.compile(pattern) ?: return null
        val entries = matchingTabs(AutoGroupRule(true, RuleKind.PATH_REGEX, pattern))
        return MatchHint.html(BetterEditorTabsBundle.message("popup.rule.regex.matches"), entries, regex)
            ?: StringUtil.escapeXmlEntities(BetterEditorTabsBundle.message("popup.rule.regex.noMatches"))
    }

    /** The match hint showing under the regex field (HTML body), or null. For tests. */
    val hintText: String? get() = shownHint

    /** The rule as it would match right now: ticked with a valid value; null otherwise (nothing to preview). */
    fun previewRule(): AutoGroupRule? {
        if (!autoGroupBox.isSelected) return null
        val kind = selectedKind
        return currentValue(kind)?.let { AutoGroupRule(true, kind, it) }
    }

    /** The value of the field of [kind]: the module name, the directory's VFS URL or the pattern; null if empty or invalid. */
    private fun currentValue(kind: RuleKind): String? = when (kind) {
        RuleKind.MODULE -> (moduleCombo.selectedItem as? String)?.takeIf { it.isNotEmpty() }
        RuleKind.DIRECTORY -> directory(directoryField.text)?.url
        RuleKind.PATH_REGEX -> regexField.text.takeIf { RuleMatching.compile(it) != null }
    }

    /** The error to show for [kind]'s field; null if it is valid or still empty. */
    private fun errorFor(kind: RuleKind): String? = when (kind) {
        RuleKind.MODULE -> null
        RuleKind.DIRECTORY ->
            if (directoryField.text.isBlank() || directory(directoryField.text) != null) null
            else BetterEditorTabsBundle.message("popup.rule.directory.error")
        RuleKind.PATH_REGEX -> {
            val pattern = regexField.text
            if (pattern.isEmpty() || RuleMatching.compile(pattern) != null) null
            else BetterEditorTabsBundle.message("popup.rule.regex.error", syntaxProblem(pattern))
        }
    }

    private fun syntaxProblem(pattern: String): String = try {
        Regex(pattern)
        ""
    } catch (e: PatternSyntaxException) {
        e.description ?: ""
    }

    /** Applies the checkbox and the type to the controls; asks the owner to re-pack if the size changed. */
    private fun refresh() {
        val kind = selectedKind
        val ticked = autoGroupBox.isSelected
        val heightBefore = if (loading) 0 else component.preferredSize.height
        kindCombo.isEnabled = ticked
        moduleCombo.isEnabled = ticked
        directoryField.isEnabled = ticked
        regexField.isEnabled = ticked
        val sizeBefore = Triple(moduleRow.isVisible to directoryRow.isVisible, regexRow.isVisible, errorRow.isVisible)
        moduleRow.isVisible = kind == RuleKind.MODULE
        directoryRow.isVisible = kind == RuleKind.DIRECTORY
        regexRow.isVisible = kind == RuleKind.PATH_REGEX
        val error = if (ticked) errorFor(kind) else null
        val errorChanged = error != shownError
        shownError = error
        errorLabel.text = error?.let { "<html>${StringUtil.escapeXmlEntities(it)}</html>" } ?: ""
        errorRow.isVisible = error != null
        val hint = if (ticked && error == null && kind == RuleKind.PATH_REGEX) matchHint() else null
        val hintChanged = hint != shownHint
        shownHint = hint
        hintLabel.text = hint?.let { "<html>$it</html>" } ?: ""
        hintRow.isVisible = hint != null
        val sizeAfter = Triple(moduleRow.isVisible to directoryRow.isVisible, regexRow.isVisible, errorRow.isVisible)
        if (!loading && (sizeBefore != sizeAfter || errorChanged || hintChanged)) {
            component.revalidate()
            // only a change of height needs a re-pack (the width is the fields' width): typing a regex changes the hint's text
            // with every letter, mostly without changing its height
            if (component.preferredSize.height != heightBefore) onResized()
        }
    }

    /** The existing directory inside the project that [text] names, or null (see the class comment). */
    private fun directory(text: String): VirtualFile? {
        val path = text.trim()
        if (path.isEmpty()) return null
        val file = resolveDirectory(FileUtil.toSystemIndependentName(path)) ?: return null
        if (!file.isValid || !file.isDirectory) return null
        val inProject = ReadAction.computeBlocking<Boolean, RuntimeException> { ProjectFileIndex.getInstance(project).isInContent(file) }
        return file.takeIf { inProject }
    }

    private fun browse() {
        val roots = ReadAction.computeBlocking<List<VirtualFile>, RuntimeException> {
            ProjectRootManager.getInstance(project).contentRoots.toList()
        }.ifEmpty { listOfNotNull(project.guessProjectDir()) }
        val descriptor: FileChooserDescriptor = FileChooserDescriptorFactory.singleDir()
            .withTitle(BetterEditorTabsBundle.message("popup.rule.directory.chooserTitle"))
            .withDescription(BetterEditorTabsBundle.message("popup.rule.directory.chooserDescription"))
            .withRoots(roots)
        val toSelect = directory(directoryField.text) ?: roots.firstOrNull()
        chooserOpen = true
        val chosen = try {
            FileChooser.chooseFile(descriptor, directoryField.textField, project, toSelect)
        }
        finally {
            chooserOpen = false
        }
        if (chosen != null) directoryField.text = FileUtil.toSystemDependentName(chosen.path)
    }

    private fun moduleNames(extra: String?): List<String> {
        val names = ReadAction.computeBlocking<List<String>, RuntimeException> {
            ModuleManager.getInstance(project).modules.map { it.name }
        }.toMutableList()
        // a module that was deleted since the rule was saved stays selectable, so that the rule is not lost by opening the popup
        if (extra != null && extra !in names) names += extra
        return names.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    private fun kindLabel(kind: RuleKind?): String = when (kind) {
        RuleKind.MODULE -> BetterEditorTabsBundle.message("popup.rule.kind.module")
        RuleKind.DIRECTORY -> BetterEditorTabsBundle.message("popup.rule.kind.directory")
        RuleKind.PATH_REGEX -> BetterEditorTabsBundle.message("popup.rule.kind.regex")
        null -> ""
    }

    /** The field text for a stored directory URL: the directory's path if it still exists, otherwise the path part of the URL. */
    private fun displayPath(url: String): String {
        val path = VirtualFileManager.getInstance().findFileByUrl(url)?.path ?: VfsUtilCore.urlToPath(url)
        return FileUtil.toSystemDependentName(path)
    }

    /** One row of the body: [child] stretched to the width, indented like the name field. */
    private fun row(child: JComponent, gap: Int, top: Int = 0): JComponent =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            background = JBUI.CurrentTheme.Popup.BACKGROUND // Islands: rounded fields and combos fill their corners with this
            alignmentX = Component.LEFT_ALIGNMENT
            border = EmptyBorder(top, textInset, gap, textInset)
            add(child, BorderLayout.CENTER)
        }

    companion object {
        private const val GAP = 6
        private const val TOP_GAP = 4

        /** The default [resolveDirectory]: the directory at [path] on the local disk. */
        fun resolveOnDisk(path: String): VirtualFile? = LocalFileSystem.getInstance().findFileByPath(path)
    }
}

/**
 * A small HTML label as wide as the popup's fields ([width]), whatever its text: a wrapped HTML label otherwise asks for the width of
 * its unwrapped text and widens the popup. Its height is that of the text wrapped to that width.
 */
private class WrappingLabel(private val width: () -> Int) : JBLabel() {
    override fun getPreferredSize(): Dimension {
        val w = width()
        val view = getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey) as? javax.swing.text.View
            ?: return Dimension(w, super.getPreferredSize().height)
        view.setSize(w.toFloat(), 0f)
        val insets = insets
        return Dimension(w, view.getPreferredSpan(javax.swing.text.View.Y_AXIS).toInt() + insets.top + insets.bottom)
    }

    override fun getMinimumSize(): Dimension = preferredSize
    override fun getMaximumSize(): Dimension = preferredSize
}
