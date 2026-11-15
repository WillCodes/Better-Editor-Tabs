package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteTrigger
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor

/**
 * Application-level settings (PERS-2, §14), stored in `betterEditorTabs.xml`, edited on the settings page (`settings/TabGroupsConfigurable`).
 * Read the raw values through [state]; the convenience accessors below return the clamped, usable values.
 *
 * Every setting takes effect without a restart: most are read where they are used, at the moment they are used; what is cached
 * (the tab bars' [dev.willcodes.bettereditortabs.tabgroups.tabbar.StripStyle]: SET-1, SET-2, SET-10) is re-applied when [changed] is
 * called, which whoever writes the state outside a "Don't ask again" answer must do (the settings page's `apply`).
 */
@Service(Service.Level.APP)
@State(name = "BetterEditorTabsSettings", storages = [Storage("betterEditorTabs.xml")])
class TabGroupsSettings : SimplePersistentStateComponent<TabGroupsSettings.State>(State()) {

    /** The persisted values with the §14 defaults. */
    class State : BaseState() {
        /** SET-1: tab-tint opacity in percent, 10 to 100 (headers are always opaque, HDR-1). */
        var opacityPercent by property(DEFAULT_OPACITY_PERCENT)

        /** SET-2: maximum displayed group-name length. */
        var maxNameLength by property(DEFAULT_MAX_NAME_LENGTH)

        /** SET-3: [GroupColor] name of the fixed colour for new groups, or null for the next unused colour. */
        var newGroupColor by string(null)

        /**
         * SET-3a: [GroupColor] name of the colour that "next unused" starts from, going round the table order; null for the first colour.
         * (Added 2026-11-15, user request.)
         */
        var startGroupColor by string(null)

        /** SET-4: confirm before deleting a non-empty group. */
        var confirmDelete by property(true)

        /** SET-5: confirm before deleting a non-empty group with middle-click. */
        var confirmMiddleClickDelete by property(true)

        /** SET-6: auto-expand a collapsed group when navigating to one of its files. */
        var autoExpandOnNavigate by property(true)

        /** SET-7: show the tab list when hovering over a collapsed group. */
        var showHoverList by property(true)

        /** SET-8: ask to move matching open tabs when an auto-group rule is set. */
        var askToMoveMatchingTabs by property(true)

        /**
         * LIFE-3a "Don't ask again": the remembered answer to the linked-instances delete dialog, `MOVE` or `CLOSE`; null asks every time.
         * (Added 2026-11-13, user request; shown on the settings page as an "ask" toggle.)
         */
        var linkedDeleteChoice by string(null)

        /**
         * MEM-5 amended "Don't ask again": the remembered answer to the "empty group, delete it?" question, `DELETE` or `KEEP`; null asks
         * every time. (Added 2026-11-14; shown on the settings page as an "ask" toggle.)
         */
        var emptyGroupChoice by string(null)

        /**
         * RULE-6 amended "Don't ask again": the remembered answer to the question about an empty group *with* an enabled rule ("keep it so
         * that new matching files join it?"), `DELETE` or `KEEP`; null asks every time. Separate from [emptyGroupChoice]. (Added 2026-11-14;
         * shown on the settings page as an "ask" toggle.)
         */
        var emptyRuledGroupChoice by string(null)

        /** SET-10: animate collapsing and expanding groups (COLL-7). */
        var animateCollapse by property(true)
    }

    /** SET-1 as a fraction, clamped to 0.10..1.0. */
    val opacity: Double get() = (state.opacityPercent / 100.0).coerceIn(0.10, 1.0)

    /** SET-2, at least 1. */
    val nameLimit: Int get() = maxOf(1, state.maxNameLength)

    /** SET-3: the fixed colour for new groups, or null for "next unused" (also for an unknown stored name). */
    val fixedNewGroupColor: GroupColor?
        get() = state.newGroupColor?.let { name -> GroupColor.values().firstOrNull { it.name == name } }

    /** SET-3a: the colour that "next unused" starts from; the first palette colour when unset or unknown. */
    val startColor: GroupColor
        get() = state.startGroupColor?.let { name -> GroupColor.values().firstOrNull { it.name == name } } ?: GroupColor.values().first()

    /** LIFE-3b: whether Delete Group from [trigger] asks before closing tabs: SET-4 for the menu, SET-5 for middle-click. */
    fun confirmsDelete(trigger: DeleteTrigger): Boolean = when (trigger) {
        DeleteTrigger.MENU -> state.confirmDelete
        DeleteTrigger.MIDDLE_CLICK -> state.confirmMiddleClickDelete
    }

    /** LIFE-3b "Don't ask again": switches off the confirmation of [trigger] only. */
    fun stopConfirmingDelete(trigger: DeleteTrigger) {
        when (trigger) {
            DeleteTrigger.MENU -> state.confirmDelete = false
            DeleteTrigger.MIDDLE_CLICK -> state.confirmMiddleClickDelete = false
        }
    }

    /** LIFE-3a: the remembered answer of the linked-instances delete dialog, or null to ask. */
    val linkedDeleteChoice: LinkedDeleteAnswer?
        get() = when (state.linkedDeleteChoice) {
            LinkedDeleteAnswer.MOVE.name -> LinkedDeleteAnswer.MOVE
            LinkedDeleteAnswer.CLOSE.name -> LinkedDeleteAnswer.CLOSE
            else -> null
        }

    /** LIFE-3a "Don't ask again": remembers [answer] ([LinkedDeleteAnswer.MOVE] or [LinkedDeleteAnswer.CLOSE]). */
    fun rememberLinkedDelete(answer: LinkedDeleteAnswer) {
        if (answer == LinkedDeleteAnswer.MOVE || answer == LinkedDeleteAnswer.CLOSE) state.linkedDeleteChoice = answer.name
    }

    /** MEM-5 amended: the remembered answer of the empty-group question, or null to ask. */
    val emptyGroupChoice: EmptyGroupAnswer?
        get() = EmptyGroupAnswer.values().firstOrNull { it.name == state.emptyGroupChoice }

    /** MEM-5 amended "Don't ask again": remembers [answer]. */
    fun rememberEmptyGroup(answer: EmptyGroupAnswer) {
        state.emptyGroupChoice = answer.name
    }

    /** RULE-6 amended: the remembered answer of the empty-group question for a group with an enabled rule, or null to ask. */
    val emptyRuledGroupChoice: EmptyGroupAnswer?
        get() = EmptyGroupAnswer.values().firstOrNull { it.name == state.emptyRuledGroupChoice }

    /** RULE-6 amended "Don't ask again": remembers [answer] for groups with an enabled rule. */
    fun rememberEmptyRuledGroup(answer: EmptyGroupAnswer) {
        state.emptyRuledGroupChoice = answer.name
    }

    /** MEM-5 / RULE-6 amended: the remembered answer for a group [ruled] or not, or null to ask. */
    fun emptyChoice(ruled: Boolean): EmptyGroupAnswer? = if (ruled) emptyRuledGroupChoice else emptyGroupChoice

    /** MEM-5 / RULE-6 amended "Don't ask again": remembers [answer] for groups [ruled] or not. */
    fun rememberEmpty(ruled: Boolean, answer: EmptyGroupAnswer) {
        if (ruled) rememberEmptyRuledGroup(answer) else rememberEmptyGroup(answer)
    }

    /** Tells every listener of [TabGroupsSettingsListener.TOPIC] that the settings changed (call after writing [state]). EDT. */
    fun changed() {
        ApplicationManager.getApplication().messageBus.syncPublisher(TabGroupsSettingsListener.TOPIC).settingsChanged()
    }

    companion object {
        const val DEFAULT_OPACITY_PERCENT = 25
        const val DEFAULT_MAX_NAME_LENGTH = 20

        fun getInstance(): TabGroupsSettings = ApplicationManager.getApplication().getService(TabGroupsSettings::class.java)
    }
}

/**
 * The plugin settings changed ([TabGroupsSettings.changed], published on the application message bus, on the EDT). Registered in
 * `plugin.xml` by [TabGroupsSettingsApplier], which re-applies the cached look to every open project.
 */
fun interface TabGroupsSettingsListener {
    fun settingsChanged()

    companion object {
        @JvmField
        @Topic.AppLevel
        val TOPIC: Topic<TabGroupsSettingsListener> = Topic(TabGroupsSettingsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
