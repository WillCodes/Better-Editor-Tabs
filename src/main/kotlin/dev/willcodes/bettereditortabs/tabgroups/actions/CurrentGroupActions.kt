package dev.willcodes.bettereditortabs.tabgroups.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteTrigger
import dev.willcodes.bettereditortabs.tabgroups.ui.popup.GroupPopup

/** Rename Current Group: opens the group popup with the name field focused. Disabled when the context tab is ungrouped. */
class RenameCurrentGroupAction : GroupAction() {
    override fun isEnabled(scope: GroupActionScope) = scope.groupId != null

    override fun perform(scope: GroupActionScope, e: AnActionEvent) {
        val id = scope.groupId ?: return
        GroupPopup.showForGroup(scope.project, scope.split, id, e.dataContext)
    }
}

/** Ungroup Current Group: the group goes, its tabs stay open. */
class UngroupCurrentGroupAction : GroupAction() {
    override fun isEnabled(scope: GroupActionScope) = scope.groupId != null

    override fun perform(scope: GroupActionScope, e: AnActionEvent) {
        scope.service.ungroup(scope.split, scope.groupId ?: return)
    }
}

/** Stash Current Group: closes the group and its tabs and saves it to the stash (Restore Tab Group... brings it back). */
class StashCurrentGroupAction : GroupAction() {
    override fun isEnabled(scope: GroupActionScope) = scope.groupId != null

    override fun perform(scope: GroupActionScope, e: AnActionEvent) {
        scope.service.stashGroup(scope.split, scope.groupId ?: return)
    }
}

/** Delete Current Group: asks first as the menu does. */
class DeleteCurrentGroupAction : GroupAction() {
    override fun isEnabled(scope: GroupActionScope) = scope.groupId != null

    override fun perform(scope: GroupActionScope, e: AnActionEvent) {
        scope.service.requestDelete(scope.split, scope.groupId ?: return, DeleteTrigger.MENU)
    }
}
