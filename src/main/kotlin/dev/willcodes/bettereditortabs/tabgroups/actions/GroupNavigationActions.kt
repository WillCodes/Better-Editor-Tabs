package dev.willcodes.bettereditortabs.tabgroups.actions

import com.intellij.openapi.actionSystem.AnActionEvent

private fun GroupActionScope.canMove(next: Boolean): Boolean {
    val tab = context ?: return false
    return !tab.pinned && service.canMoveToAdjacentGroup(split, tab.file, next)
}

private fun GroupActionScope.move(next: Boolean) {
    val tab = context ?: return
    service.moveToAdjacentGroup(split, tab.file, next)
}

/** Move Tab to Next Group: the tab goes to the end of the group after its own (the first group after it, if ungrouped), as when picking that group in the Add to Group menu. */
class MoveTabToNextGroupAction : GroupAction() {
    override fun isEnabled(scope: GroupActionScope) = scope.canMove(next = true)

    override fun perform(scope: GroupActionScope, e: AnActionEvent) = scope.move(next = true)
}

/** Move Tab to Previous Group: like [MoveTabToNextGroupAction], the other way. */
class MoveTabToPreviousGroupAction : GroupAction() {
    override fun isEnabled(scope: GroupActionScope) = scope.canMove(next = false)

    override fun perform(scope: GroupActionScope, e: AnActionEvent) = scope.move(next = false)
}

private fun GroupActionScope.canSelect(): Boolean = context?.let { service.canSelectInGroup(split, it.file) } ?: false

private fun GroupActionScope.select(next: Boolean) {
    val tab = context ?: return
    service.selectInGroup(split, tab.file, next)
}

/** Select Next Tab in Group: selects and focuses the next open tab of the current group, cycling at the end. */
class SelectNextTabInGroupAction : GroupAction() {
    override fun isEnabled(scope: GroupActionScope) = scope.canSelect()

    override fun perform(scope: GroupActionScope, e: AnActionEvent) = scope.select(next = true)
}

/** Select Previous Tab in Group: selects and focuses the previous open tab of the current group, cycling at the start. */
class SelectPreviousTabInGroupAction : GroupAction() {
    override fun isEnabled(scope: GroupActionScope) = scope.canSelect()

    override fun perform(scope: GroupActionScope, e: AnActionEvent) = scope.select(next = false)
}
