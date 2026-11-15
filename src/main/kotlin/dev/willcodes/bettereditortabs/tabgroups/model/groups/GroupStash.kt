package dev.willcodes.bettereditortabs.tabgroups.model.groups

import dev.willcodes.bettereditortabs.tabgroups.model.persist.rewriteUrl

/**
 * One stashed group: what Stash Group saved. [groupId] lets a restore re-link the group with the identity that still
 * exists in other splits; [name] and [color] are the group's at the time; [files] are the member files as VFS URLs, in tab
 * order; [stashedAt] is epoch milliseconds. [id] identifies the entry.
 *
 * [rule] is the group's auto-group rule at the time (a restore brings it back); null if it had none. A snapshot with no
 * [files] is an empty group with an enabled rule: restoring it makes an empty instance again.
 */
data class StashEntry(
    val id: String,
    val groupId: String,
    val name: String,
    val color: GroupColor,
    val files: List<String>,
    val stashedAt: Long,
    val rule: AutoGroupRule? = null,
)

/**
 * The project's stash: snapshots of groups that were closed with Stash Group, newest first. Kept apart from
 * [GroupsState], so a stashed group is not a group of the project (the Add to Group submenu and the colour assignment ignore it).
 *
 * Threading: mutated on the EDT only; [entries] is an immutable list in a volatile field, so any thread may read it.
 */
class GroupStash(initial: List<StashEntry> = emptyList()) {
    /** The entries, newest first. Never mutated; replaced as a whole. */
    @Volatile
    var entries: List<StashEntry> = initial
        private set

    fun get(id: String): StashEntry? = entries.firstOrNull { it.id == id }

    /** Adds [entry] as the newest. An entry with the same id is replaced. */
    fun add(entry: StashEntry) {
        entries = listOf(entry) + entries.filter { it.id != entry.id }
    }

    /** Removes the entry [id]; false if there is none. */
    fun remove(id: String): Boolean = take(id) != null

    /** Removes and returns the entry [id]; null if there is none. */
    fun take(id: String): StashEntry? {
        val entry = get(id) ?: return null
        entries = entries.filter { it.id != id }
        return entry
    }

    /** Replaces every entry (loading the saved state). */
    fun replaceAll(list: List<StashEntry>) {
        entries = list.toList()
    }

    /** A file or directory moved from [oldUrl] to [newUrl]. Returns whether any entry changed. */
    fun rewriteUrls(oldUrl: String, newUrl: String): Boolean {
        var changed = false
        entries = entries.map { e ->
            val files = e.files.map { rewriteUrl(it, oldUrl, newUrl) }
            val rule = e.rule?.withUrlMoved(oldUrl, newUrl)
            if (files == e.files && rule === e.rule) e else e.copy(files = files, rule = rule).also { changed = true }
        }
        return changed
    }
}
