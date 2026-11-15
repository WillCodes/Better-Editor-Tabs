package dev.willcodes.bettereditortabs.tabgroups.actions

import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Toggle Collapse of Current Group: like a click on the header (a collapsed or partly shown group expands fully, an expanded one
 * collapses). Collapsing moves the selection out of the group, so with an ungrouped context tab the action targets the group
 * it collapsed last in this split, while that is still collapsed; otherwise it is disabled.
 */
class ToggleCollapseCurrentGroupAction : GroupAction() {
    override fun isEnabled(scope: GroupActionScope) = scope.service.canToggleCurrentGroup(scope.split, scope.context?.file)

    override fun perform(scope: GroupActionScope, e: AnActionEvent) {
        scope.service.toggleCurrentGroup(scope.split, scope.context?.file)
    }
}

/** Collapse Other Groups: every expanded or partly shown group of the split except the current one (all of them for an ungrouped tab). */
class CollapseOtherGroupsAction : GroupAction() {
    override fun isEnabled(scope: GroupActionScope) = scope.service.canCollapseOtherGroups(scope.split, scope.context?.file)

    override fun perform(scope: GroupActionScope, e: AnActionEvent) {
        scope.service.collapseOtherGroups(scope.split, scope.context?.file)
    }
}

/** Expand All Groups: every collapsed or partly shown group of the current split expands fully. Needs no current group. */
class ExpandAllGroupsAction : GroupAction() {
    override fun isEnabled(scope: GroupActionScope) = scope.service.canExpandAllGroups(scope.split)

    override fun perform(scope: GroupActionScope, e: AnActionEvent) {
        scope.service.expandAllGroups(scope.split)
    }
}
