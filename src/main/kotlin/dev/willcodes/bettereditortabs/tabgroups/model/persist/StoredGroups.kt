package dev.willcodes.bettereditortabs.tabgroups.model.persist

import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor
import dev.willcodes.bettereditortabs.tabgroups.model.splits.SplitKey

/**
 * The stored form of the groups (PERS-1) as plain, immutable data: no IDE types, no XML annotations, so the restore rules can be
 * unit tested. `service/GroupsPersistence` converts it to and from the XML beans of the workspace file.
 *
 * Files are stored as VFS URLs ([StoredInstance.members], [StoredSplit.order]), colours by [GroupColor] name, split keys encoded
 * ([SplitKey.encode]). Room for later stages (PERS-4, RULE, stash): they add fields to [StoredGroup] (rule) and to [StoredGroups]
 * (stash) with defaults, and the XML beans ignore elements they do not know, so data written by this version stays readable.
 */
data class StoredGroup(val id: String, val name: String, val color: String)

/** One group instance of a split: members in tab order and its collapsed flag (COLL-5). */
data class StoredInstance(val groupId: String, val members: List<String>, val collapsed: Boolean)

/**
 * One split with group instances. [key] is the encoded [SplitKey]; [order] is the split's whole *logical* tab order (every open tab,
 * the hidden tabs of a collapsed group at its header's place), because the platform reopens hidden tabs at the end of the strip;
 * [instances] are in strip order.
 */
data class StoredSplit(val key: String, val order: List<String>, val instances: List<StoredInstance>) {
    fun splitKey(): SplitKey = SplitKey.decode(key) ?: SplitKey("?", "")
}

data class StoredGroups(val groups: List<StoredGroup>, val splits: List<StoredSplit>) {
    companion object {
        val EMPTY = StoredGroups(emptyList(), emptyList())
    }
}

/** The colour of a stored name; grey for a name this version does not know (written by a newer version, or edited by hand). */
fun colorFromName(name: String?): GroupColor = GroupColor.values().firstOrNull { it.name == name } ?: GroupColor.GREY

/**
 * MEM-4 for state that has not been restored yet: a file or directory was renamed or moved, so every stored URL at or below [oldUrl]
 * now starts with [newUrl]. Pure string work on VFS URLs (`protocol://path`).
 */
fun rewriteUrl(url: String, oldUrl: String, newUrl: String): String = when {
    url == oldUrl -> newUrl
    url.startsWith("$oldUrl/") -> newUrl + url.substring(oldUrl.length)
    else -> url
}

/** [rewriteUrl] over every file URL of the stored state. */
fun StoredGroups.rewriteUrls(oldUrl: String, newUrl: String): StoredGroups {
    fun fix(urls: List<String>) = urls.map { rewriteUrl(it, oldUrl, newUrl) }
    return copy(
        splits = splits.map { s ->
            s.copy(order = fix(s.order), instances = s.instances.map { it.copy(members = fix(it.members)) })
        },
    )
}

/**
 * PERS-3 pending splits: this (the live state, captured) plus [pending] stored splits that no live split has claimed yet, so a save
 * during the waiting time keeps them. Group identities already present here win (the live name and colour); pending splits come last.
 */
fun StoredGroups.withPending(pending: StoredGroups): StoredGroups {
    if (pending.splits.isEmpty()) return this
    val known = groups.mapTo(HashSet()) { it.id }
    return StoredGroups(groups + pending.groups.filter { it.id !in known }, splits + pending.splits)
}
