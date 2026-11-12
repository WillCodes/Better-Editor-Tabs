package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteTrigger
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor

/**
 * Application-level settings (PERS-2, §14), stored in `betterEditorTabs.xml`. Defaults only; the settings page comes in Stage 12.
 * Read the raw values through [state]; the convenience accessors below return the clamped, usable values.
 */
@Service(Service.Level.APP)
@State(name = "BetterEditorTabsSettings", storages = [Storage("betterEditorTabs.xml")])
class TabGroupsSettings : SimplePersistentStateComponent<TabGroupsSettings.State>(State()) {

    /** The persisted values with the §14 defaults. */
    class State : BaseState() {
        /** SET-1: header and tab-tint opacity in percent, 10 to 100. */
        var opacityPercent by property(DEFAULT_OPACITY_PERCENT)

        /** SET-2: maximum displayed group-name length. */
        var maxNameLength by property(DEFAULT_MAX_NAME_LENGTH)

        /** SET-3: [GroupColor] name of the fixed colour for new groups, or null for the next unused colour. */
        var newGroupColor by string(null)

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
    }

    /** SET-1 as a fraction, clamped to 0.10..1.0. */
    val opacity: Double get() = (state.opacityPercent / 100.0).coerceIn(0.10, 1.0)

    /** SET-2, at least 1. */
    val nameLimit: Int get() = maxOf(1, state.maxNameLength)

    /** SET-3: the fixed colour for new groups, or null for "next unused" (also for an unknown stored name). */
    val fixedNewGroupColor: GroupColor?
        get() = state.newGroupColor?.let { name -> GroupColor.values().firstOrNull { it.name == name } }

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

    companion object {
        const val DEFAULT_OPACITY_PERCENT = 25
        const val DEFAULT_MAX_NAME_LENGTH = 20

        fun getInstance(): TabGroupsSettings = ApplicationManager.getApplication().getService(TabGroupsSettings::class.java)
    }
}
