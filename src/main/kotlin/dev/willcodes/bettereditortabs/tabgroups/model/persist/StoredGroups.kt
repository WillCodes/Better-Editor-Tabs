package dev.willcodes.bettereditortabs.tabgroups.model.persist

import dev.willcodes.bettereditortabs.tabgroups.model.groups.AutoGroupRule
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor
import dev.willcodes.bettereditortabs.tabgroups.model.groups.RuleKind
import dev.willcodes.bettereditortabs.tabgroups.model.splits.SplitKey

/**
 * The stored form of the groups (PERS-1) as plain, immutable data: no IDE types, no XML annotations, so the restore rules can be
 * unit tested. `service/GroupsPersistence` converts it to and from the XML beans of the workspace file.
 *
 * Files are stored as VFS URLs ([StoredInstance.members], [StoredSplit.order]), colours by [GroupColor] name, split keys encoded
 * ([SplitKey.encode]). Fields added later (the stash, the rule) have defaults, and the XML beans ignore elements they do not know and
 * keep the defaults of those missing, so data written by another version stays readable.
 */
data class StoredGroup(val id: String, val name: String, val color: String, val rule: StoredRule? = null)

/**
 * A stored auto-group rule (RULE-1): the [kind] by [RuleKind] name, the [value] (module name, directory URL or pattern) and whether it
 * is [enabled]. A kind name this version does not know gives no rule ([toRule]).
 */
data class StoredRule(val kind: String, val value: String, val enabled: Boolean) {
    fun toRule(): AutoGroupRule? = RuleKind.values().firstOrNull { it.name == kind }?.let { AutoGroupRule(enabled, it, value) }

    /** MEM-4: a [RuleKind.DIRECTORY] rule's value follows a moved file or directory. */
    fun withUrlMoved(oldUrl: String, newUrl: String): StoredRule =
        if (kind == RuleKind.DIRECTORY.name) copy(value = rewriteUrl(value, oldUrl, newUrl)) else this
}

fun AutoGroupRule.toStored(): StoredRule = StoredRule(kind.name, value, enabled)

/**
 * One group instance of a split: members in tab order and its collapsed flag (COLL-5). [place]: only for an instance stored *empty*
 * (PERS-3 amended: the user kept it, ruled or not), the number of tabs of the split's logical order in front of its pill; null for an
 * instance with members, and in files written before this existed.
 */
data class StoredInstance(val groupId: String, val members: List<String>, val collapsed: Boolean, val place: Int? = null)

/**
 * One split with group instances. [key] is the encoded [SplitKey]; [order] is the split's whole *logical* tab order (every open tab,
 * the hidden tabs of a collapsed group at its header's place), because the platform reopens hidden tabs at the end of the strip;
 * [instances] are in strip order. [manualOrder]: while the plugin sorts the split alphabetically (IDE-2), the manual order the strip
 * goes back to when sorting is switched off, so it survives a restart; empty otherwise.
 */
data class StoredSplit(
    val key: String,
    val order: List<String>,
    val instances: List<StoredInstance>,
    val manualOrder: List<String> = emptyList(),
) {
    fun splitKey(): SplitKey = SplitKey.decode(key) ?: SplitKey("?", "")

    /**
     * SPLIT-6: true for a split that held no tab at all, only empty group instances: a temporary split (kept open by a placeholder tab
     * that is never stored) or a window whose last tab closed. No file to match it by, so a restart never finds it again; its empty
     * instances are restored into the main area's first split instead ([RestorePlanner]).
     */
    val isEmptyOnly: Boolean get() = order.isEmpty() && instances.isNotEmpty() && instances.all { it.members.isEmpty() }
}

/**
 * One stashed group (LIFE-2, PERS-4): the group's identity, name, [color] ([GroupColor] name), member file URLs in order and the time
 * it was stashed, and the group's [rule] then (null if none).
 */
data class StoredStash(
    val id: String,
    val groupId: String,
    val name: String,
    val color: String,
    val files: List<String>,
    val stashedAt: Long,
    val rule: StoredRule? = null,
)

/** [stash]: the stashed groups, newest first (PERS-4); stored independently of splits, a restart never touches it. */
data class StoredGroups(
    val groups: List<StoredGroup>,
    val splits: List<StoredSplit>,
    val stash: List<StoredStash> = emptyList(),
) {
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
            s.copy(order = fix(s.order), instances = s.instances.map { it.copy(members = fix(it.members)) }, manualOrder = fix(s.manualOrder))
        },
        stash = stash.map { it.copy(files = fix(it.files), rule = it.rule?.withUrlMoved(oldUrl, newUrl)) },
        groups = groups.map { it.copy(rule = it.rule?.withUrlMoved(oldUrl, newUrl)) },
    )
}

/**
 * PERS-3 pending splits: this (the live state, captured) plus [pending] stored splits that no live split has claimed yet, so a save
 * during the waiting time keeps them. Group identities already present here win (the live name and colour); pending splits come last.
 */
fun StoredGroups.withPending(pending: StoredGroups): StoredGroups {
    if (pending.splits.isEmpty()) return this
    val known = groups.mapTo(HashSet()) { it.id }
    return StoredGroups(groups + pending.groups.filter { it.id !in known }, splits + pending.splits, stash)
}
