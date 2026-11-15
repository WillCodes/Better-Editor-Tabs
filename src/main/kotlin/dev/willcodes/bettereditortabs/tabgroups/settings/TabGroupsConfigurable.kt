package dev.willcodes.bettereditortabs.tabgroups.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.HyperlinkEventAction
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.ColorIcon
import com.intellij.util.ui.JBUI
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor
import dev.willcodes.bettereditortabs.tabgroups.model.settings.RememberedAnswer
import dev.willcodes.bettereditortabs.tabgroups.service.EmptyGroupAnswer
import dev.willcodes.bettereditortabs.tabgroups.service.GroupColors
import dev.willcodes.bettereditortabs.tabgroups.service.LinkedDeleteAnswer
import dev.willcodes.bettereditortabs.tabgroups.service.StashedGroup
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupStashListener
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsSettings
import java.awt.Dimension
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSlider

/**
 * The settings page Settings > Editor > General > Editor Tabs - Groups: tint opacity, name length, new-group colour and the starting colour, behaviour and confirmation toggles, plus the remembered "Don't ask
 * again" answers shown as unticked confirmation toggles. The values are application-level; the page
 * is a project configurable only because it lists the current project's stash.
 *
 * Built with the Kotlin UI DSL. The whole page is reset, compared and written by one `onReset` / `onIsModified` / `onApply` trio at
 * the end of [createPanel], so every control's state is handled in one place. Apply writes [TabGroupsSettings.state] and then calls
 * [TabGroupsSettings.changed], so the cached strip look is re-applied in every open project at once; nothing
 * needs a restart. Removing stashed groups is pending until Apply: [pendingRemovals] hides them, Reset or Cancel brings them
 * back.
 *
 * Registered in `plugin.xml` as a project configurable under Editor > General ([PARENT_ID]), next to IntelliJ's own Editor Tabs page.
 * The name starts with "Editor Tabs" because that page's children are listed by name, so it sits right below Editor Tabs.
 * It cannot sit under Editor Tabs itself: that page is an `editorOptionsProvider` child of Editor > General, not a node of the main
 * settings tree, so a `parentId` naming it is not found and the page falls to the root of the tree. Editor Tabs' own extension point
 * (`editorTabsConfigurable`) only adds inline sections to its page, not child pages.
 */
class TabGroupsConfigurable(private val project: Project) :
    BoundSearchableConfigurable(message("settings.display.name"), ID, ID) {

    private val settings get() = TabGroupsSettings.getInstance()

    private val linkedAnswer = RememberedAnswer(
        listOf(LinkedDeleteAnswer.MOVE.name, LinkedDeleteAnswer.CLOSE.name),
        LinkedDeleteAnswer.MOVE.name,
    )
    private val emptyAnswer = RememberedAnswer(EmptyGroupAnswer.values().map { it.name }, EmptyGroupAnswer.KEEP.name)

    // The controls, created in createPanel; internal so that the tests can drive them like a user would.
    internal lateinit var opacitySlider: JSlider
    internal lateinit var opacityValue: JLabel
    internal lateinit var nameLength: JBIntSpinner
    internal lateinit var newColor: ComboBox<GroupColor?>
    internal lateinit var startColor: ComboBox<GroupColor>
    internal lateinit var autoExpand: JBCheckBox
    internal lateinit var hoverList: JBCheckBox
    internal lateinit var animate: JBCheckBox
    internal lateinit var confirmDelete: JBCheckBox
    internal lateinit var confirmMiddleClick: JBCheckBox
    internal lateinit var askMoveMatching: JBCheckBox
    internal lateinit var askLinkedDelete: JBCheckBox
    internal lateinit var linkedDeleteAnswer: ComboBox<LinkedDeleteAnswer>
    internal lateinit var askEmptyGroup: JBCheckBox
    internal lateinit var emptyGroupAnswer: ComboBox<EmptyGroupAnswer>
    internal lateinit var askEmptyRuledGroup: JBCheckBox
    internal lateinit var emptyRuledGroupAnswer: ComboBox<EmptyGroupAnswer>
    internal lateinit var stashList: JBList<StashedGroup>

    private val stashModel = CollectionListModel<StashedGroup>()

    /** The entry ids whose removal waits for Apply. */
    internal val pendingRemovals = LinkedHashSet<String>()

    /** Lives from [createPanel] to [disposeUIResources]; holds the stash subscription. */
    private var uiDisposable: Disposable? = null

    override fun createPanel(): DialogPanel {
        val disposable = Disposer.newDisposable("TabGroupsConfigurable")
        uiDisposable = disposable
        val result = panel {
            group(message("settings.group.appearance"), true) { appearance() }
            group(message("settings.group.behaviour"), true) { behaviour() }
            group(message("settings.group.confirmations"), true) { confirmations() }
            group(message("settings.group.stash"), true) { stash() }
            onReset { resetControls() }
            onIsModified { isControlsModified() }
            onApply { applyControls() }
        }
        project.messageBus.connect(disposable).subscribe(TabGroupStashListener.TOPIC, TabGroupStashListener { refreshStash() })
        refreshStash()
        return result
    }

    override fun disposeUIResources() {
        uiDisposable?.let { Disposer.dispose(it) }
        uiDisposable = null
        super.disposeUIResources()
    }

    // ---- layout ---------------------------------------------------------------------------------------------------------------

    /** Tint opacity, name length and group colours. */
    private fun Panel.appearance() {
        row(message("settings.opacity")) {
            opacitySlider = slider(OPACITY_MIN, OPACITY_MAX, 10, 0)
                .comment(message("settings.opacity.comment"), COMMENT_WIDTH, HyperlinkEventAction.HTML_HYPERLINK_INSTANCE)
                .component
            opacityValue = label(percent(opacitySlider.value)).component
            opacitySlider.addChangeListener { opacityValue.text = percent(opacitySlider.value) }
        }
        row(message("settings.nameLength")) {
            nameLength = spinner(NAME_LENGTH_MIN..NAME_LENGTH_MAX, 1)
                .comment(message("settings.nameLength.comment"), COMMENT_WIDTH, HyperlinkEventAction.HTML_HYPERLINK_INSTANCE)
                .component
        }
        row(message("settings.newColor")) {
            newColor = comboBox(listOf<GroupColor?>(null) + GroupColor.values(), newColorRenderer()).component
        }
        indent {
            row(message("settings.startColor")) {
                startColor = comboBox(GroupColor.values().toList(), startColorRenderer()).component
            }
        }
        // The starting colour only matters while new groups take the next unused colour
        newColor.addItemListener { startColor.isEnabled = newColor.selectedItem == null }
    }

    /** Auto-expand, hover list and animation toggles. */
    private fun Panel.behaviour() {
        plainRow {
            autoExpand = checkBox(message("settings.autoExpand"))
                .comment(message("settings.autoExpand.comment"), COMMENT_WIDTH, HyperlinkEventAction.HTML_HYPERLINK_INSTANCE)
                .component
        }
        plainRow { hoverList = checkBox(message("settings.hoverList")).component }
        plainRow { animate = checkBox(message("settings.animate")).component }
    }

    /** Delete and move-matching confirmations and the remembered "Don't ask again" answers: ticked means the question is asked. */
    private fun Panel.confirmations() {
        plainRow { confirmDelete = checkBox(message("settings.confirmDelete")).component }
        plainRow { confirmMiddleClick = checkBox(message("settings.confirmMiddleClick")).component }
        plainRow { askMoveMatching = checkBox(message("settings.askMoveMatching")).component }
        plainRow { askLinkedDelete = checkBox(message("settings.askLinkedDelete")).component }
        indent {
            row(message("settings.whenNotAsking")) {
                linkedDeleteAnswer = comboBox(listOf(LinkedDeleteAnswer.MOVE, LinkedDeleteAnswer.CLOSE), linkedRenderer()).component
            }
        }
        plainRow { askEmptyGroup = checkBox(message("settings.askEmptyGroup")).component }
        indent {
            row(message("settings.whenNotAsking")) {
                emptyGroupAnswer = comboBox(EmptyGroupAnswer.values().toList(), emptyRenderer()).component
            }
        }
        plainRow { askEmptyRuledGroup = checkBox(message("settings.askEmptyRuledGroup")).component }
        indent {
            row(message("settings.whenNotAsking")) {
                emptyRuledGroupAnswer = comboBox(EmptyGroupAnswer.values().toList(), emptyRenderer()).component
            }
        }
        // an answer combo is only live while its question is not asked
        tie(askLinkedDelete, linkedDeleteAnswer)
        tie(askEmptyGroup, emptyGroupAnswer)
        tie(askEmptyRuledGroup, emptyRuledGroupAnswer)
    }

    /** The stash list. */
    private fun Panel.stash() {
        plainRow {
            stashList = JBList(stashModel)
            stashList.cellRenderer = stashRenderer()
            stashList.emptyText.text = message("settings.stash.empty")
            val decorated = ToolbarDecorator.createDecorator(stashList)
                .disableAddAction()
                .disableUpDownActions()
                .setRemoveAction { removeSelectedStash() }
                .setPreferredSize(Dimension(JBUI.scale(STASH_LIST_WIDTH), JBUI.scale(STASH_LIST_HEIGHT)))
                .createPanel()
            cell(decorated).align(Align.FILL)
        }
    }

    // A row without a label; the label is passed explicitly so that no synthetic default-argument method of the platform is called.
    private fun Panel.plainRow(init: Row.() -> Unit) = row(null as JLabel?, init)

    private fun tie(ask: JBCheckBox, answer: ComboBox<*>) {
        ask.addItemListener { answer.isEnabled = !ask.isSelected }
    }

    // ---- state <-> controls ---------------------------------------------------------------------------------------------------

    private fun resetControls() {
        val s = settings.state
        opacitySlider.value = s.opacityPercent.coerceIn(OPACITY_MIN, OPACITY_MAX)
        opacityValue.text = percent(opacitySlider.value)
        nameLength.number = s.maxNameLength.coerceIn(NAME_LENGTH_MIN, NAME_LENGTH_MAX)
        newColor.selectedItem = settings.fixedNewGroupColor
        startColor.selectedItem = settings.startColor
        startColor.isEnabled = newColor.selectedItem == null
        autoExpand.isSelected = s.autoExpandOnNavigate
        hoverList.isSelected = s.showHoverList
        animate.isSelected = s.animateCollapse
        confirmDelete.isSelected = s.confirmDelete
        confirmMiddleClick.isSelected = s.confirmMiddleClickDelete
        askMoveMatching.isSelected = s.askToMoveMatchingTabs
        askLinkedDelete.isSelected = linkedAnswer.asks(s.linkedDeleteChoice)
        linkedDeleteAnswer.selectedItem = LinkedDeleteAnswer.valueOf(linkedAnswer.shown(s.linkedDeleteChoice))
        askEmptyGroup.isSelected = emptyAnswer.asks(s.emptyGroupChoice)
        emptyGroupAnswer.selectedItem = EmptyGroupAnswer.valueOf(emptyAnswer.shown(s.emptyGroupChoice))
        askEmptyRuledGroup.isSelected = emptyAnswer.asks(s.emptyRuledGroupChoice)
        emptyRuledGroupAnswer.selectedItem = EmptyGroupAnswer.valueOf(emptyAnswer.shown(s.emptyRuledGroupChoice))
        for (ask in listOf(askLinkedDelete to linkedDeleteAnswer, askEmptyGroup to emptyGroupAnswer, askEmptyRuledGroup to emptyRuledGroupAnswer)) {
            ask.second.isEnabled = !ask.first.isSelected
        }
        pendingRemovals.clear()
        refreshStash()
    }

    private fun isControlsModified(): Boolean {
        val s = settings.state
        return opacitySlider.value != s.opacityPercent ||
            nameLength.number != s.maxNameLength ||
            newColor.selectedItem != settings.fixedNewGroupColor ||
            startColor.selectedItem != settings.startColor ||
            autoExpand.isSelected != s.autoExpandOnNavigate ||
            hoverList.isSelected != s.showHoverList ||
            animate.isSelected != s.animateCollapse ||
            confirmDelete.isSelected != s.confirmDelete ||
            confirmMiddleClick.isSelected != s.confirmMiddleClickDelete ||
            askMoveMatching.isSelected != s.askToMoveMatchingTabs ||
            answerModified(linkedAnswer, askLinkedDelete, linkedDeleteAnswer.selectedItem, s.linkedDeleteChoice) ||
            answerModified(emptyAnswer, askEmptyGroup, emptyGroupAnswer.selectedItem, s.emptyGroupChoice) ||
            answerModified(emptyAnswer, askEmptyRuledGroup, emptyRuledGroupAnswer.selectedItem, s.emptyRuledGroupChoice) ||
            pendingRemovals.isNotEmpty()
    }

    /** A ticked box ignores the combo; unticked, the combo's answer is compared with what is stored. */
    private fun answerModified(mapping: RememberedAnswer, ask: JBCheckBox, answer: Any?, stored: String?): Boolean =
        ask.isSelected != mapping.asks(stored) || (!ask.isSelected && (answer as Enum<*>).name != mapping.shown(stored))

    private fun applyControls() {
        val s = settings.state
        s.opacityPercent = opacitySlider.value
        s.maxNameLength = nameLength.number
        s.newGroupColor = (newColor.selectedItem as GroupColor?)?.name
        s.startGroupColor = (startColor.selectedItem as GroupColor).name
        s.autoExpandOnNavigate = autoExpand.isSelected
        s.showHoverList = hoverList.isSelected
        s.animateCollapse = animate.isSelected
        s.confirmDelete = confirmDelete.isSelected
        s.confirmMiddleClickDelete = confirmMiddleClick.isSelected
        s.askToMoveMatchingTabs = askMoveMatching.isSelected
        s.linkedDeleteChoice =
            linkedAnswer.toStored(askLinkedDelete.isSelected, (linkedDeleteAnswer.selectedItem as LinkedDeleteAnswer).name)
        s.emptyGroupChoice =
            emptyAnswer.toStored(askEmptyGroup.isSelected, (emptyGroupAnswer.selectedItem as EmptyGroupAnswer).name)
        s.emptyRuledGroupChoice =
            emptyAnswer.toStored(askEmptyRuledGroup.isSelected, (emptyRuledGroupAnswer.selectedItem as EmptyGroupAnswer).name)

        val removals = pendingRemovals.toList()
        pendingRemovals.clear()
        val stashService = project.service<TabGroupsService>()
        for (id in removals) stashService.removeStashed(id)
        refreshStash()

        settings.changed()
    }

    // ---- stash list ---------------------------------------------------------------------------------------------------

    /** Shows the stash entries that are not pending removal, newest first as the service returns them. */
    private fun refreshStash() {
        if (project.isDisposed || !::stashList.isInitialized) return
        val visible = project.service<TabGroupsService>().stashedGroups().filter { it.entryId !in pendingRemovals }
        stashModel.replaceAll(visible)
    }

    private fun removeSelectedStash() {
        for (entry in stashList.selectedValuesList) pendingRemovals += entry.entryId
        refreshStash()
    }

    // ---- renderers ------------------------------------------------------------------------------------------------------------

    private fun newColorRenderer() = Renderer<GroupColor?> { label, value ->
        if (value == null) {
            label.text = message("settings.newColor.next")
        }
        else {
            label.text = message(value.messageKey)
            label.icon = swatch(value)
        }
    }

    private fun startColorRenderer() = Renderer<GroupColor> { label, value ->
        if (value != null) {
            label.text = message(value.messageKey)
            label.icon = swatch(value)
        }
    }

    private fun linkedRenderer() = Renderer<LinkedDeleteAnswer> { label, value ->
        label.text = when (value) {
            LinkedDeleteAnswer.CLOSE -> message("settings.linked.close")
            else -> message("settings.linked.move")
        }
    }

    private fun emptyRenderer() = Renderer<EmptyGroupAnswer> { label, value ->
        label.text = when (value) {
            EmptyGroupAnswer.DELETE -> message("settings.empty.delete")
            else -> message("settings.empty.keep")
        }
    }

    private fun stashRenderer() = object : ColoredListCellRenderer<StashedGroup>() {
        override fun customizeCellRenderer(
            list: JList<out StashedGroup>,
            value: StashedGroup?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            if (value == null) return
            icon = swatch(value.color)
            append(value.displayName)
            append("  " + message("restore.tabCount", value.fileCount), SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    /** A list renderer from a lambda; `SimpleListCellRenderer.create` is avoided (scheduled for removal, reported by the verifier). */
    private class Renderer<T>(private val customizer: (SimpleListCellRenderer<T>, T?) -> Unit) : SimpleListCellRenderer<T>() {
        override fun customize(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) = customizer(this, value)
    }

    private fun swatch(color: GroupColor) = ColorIcon(JBUI.scale(SWATCH_SIZE), GroupColors.jbColor(color))

    private fun percent(value: Int) = message("settings.opacity.value", value)

    companion object {
        const val ID = "dev.willcodes.bettereditortabs.tabgroups"

        /** The id of IntelliJ's Editor > General page (`EditorOptionsPanel`), this page's parent in `plugin.xml`. */
        const val PARENT_ID = "preferences.editor"

        const val OPACITY_MIN = 10
        const val OPACITY_MAX = 100
        const val NAME_LENGTH_MIN = 1
        const val NAME_LENGTH_MAX = 100
        private const val SWATCH_SIZE = 12

        /**
         * Hint width in characters. A word-wrapped hint asks for its whole text as its preferred width, which widened the page past
         * IntelliJ's minimum settings width and showed a horizontal scroll bar; a fixed width keeps every hint wrapping.
         */
        private const val COMMENT_WIDTH = 60
        private const val STASH_LIST_WIDTH = 420
        private const val STASH_LIST_HEIGHT = 120

        private fun message(key: String, vararg params: Any): String = BetterEditorTabsBundle.message(key, *params)
    }
}
