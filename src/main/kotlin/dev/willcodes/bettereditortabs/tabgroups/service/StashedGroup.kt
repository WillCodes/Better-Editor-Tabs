package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor

/**
 * One entry of the project's stash as the UI shows it: the Restore Tab Group… popup and the
 * settings page's "Stashed groups (this project)" list. Immutable; [TabGroupsService.stashedGroups] builds a fresh list on every call.
 *
 * [entryId] identifies the entry (pass it to [TabGroupsService.restoreStashed] / [TabGroupsService.removeStashed]); [displayName] is
 * the group's name, or "Unnamed group (Blue)", and [color] its colour, both taken from the live group if the group
 * still has an instance in another split (a linked group renamed there shows its new name), else from the snapshot. [fileCount] is
 * the number of files in the snapshot (whether they still exist is only checked when restoring). [stashedAt] is the time of
 * the Stash Group, in epoch milliseconds.
 */
data class StashedGroup(
    val entryId: String,
    val displayName: String,
    val color: GroupColor,
    val fileCount: Int,
    val stashedAt: Long,
)

/**
 * The project's stash changed (an entry was added, restored or removed). Published on the project's message bus, on the EDT, after
 * the change; read the new list with [TabGroupsService.stashedGroups]. For views that stay open while the stash can change (the
 * settings page).
 */
fun interface TabGroupStashListener {
    fun stashChanged()

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC: Topic<TabGroupStashListener> = Topic(TabGroupStashListener::class.java, Topic.BroadcastDirection.NONE)

        fun publish(project: Project) {
            if (!project.isDisposed) project.messageBus.syncPublisher(TOPIC).stashChanged()
        }
    }
}
