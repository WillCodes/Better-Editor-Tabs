package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * The membership state of all groups: group identities plus, per split [S], the group instances with their member files [F].
 * Pure and generic so it is testable without the platform. Not thread-safe; the caller is on the EDT. Every operation only touches
 * the split it is given, except that dropping the last instance of a group removes the identity.
 *
 * @param newId produces ids for new groups.
 */
class GroupsState<S, F>(private val newId: () -> String) {
    /** True for a group whose empty instances must stay (an enabled auto-group rule, [TabGroup.keepsEmptyInstances]). The empty-instance cleanup skips those. */
    var keepWhenEmpty: (TabGroup) -> Boolean = { it.keepsEmptyInstances }

    private val groupMap = LinkedHashMap<String, TabGroup>()
    private val bySplit = LinkedHashMap<S, MutableList<GroupInstance<F>>>()

    /**
     * The last identities removed when their last instance went away, newest last, at most [FORGOTTEN_MAX]. A group that moves to another split is removed from
     * its source first (the drag result) and arrives at the target afterwards ([acceptInstance]); this lets the arrival get the same
     * [TabGroup] back with its rule, instead of a copy made from id, name and colour.
     */
    private val forgotten = LinkedHashMap<String, TabGroup>()

    /** All groups, in creation order. */
    val groups: Collection<TabGroup> get() = groupMap.values

    fun group(id: String): TabGroup? = groupMap[id]

    /** The instances in [split], in the order they were added or last committed by [replaceSplit]. */
    fun instancesIn(split: S): List<GroupInstance<F>> = bySplit[split] ?: emptyList()

    fun instance(split: S, groupId: String): GroupInstance<F>? = bySplit[split]?.firstOrNull { it.groupId == groupId }

    /** The instance in [split] containing [file], if any. */
    fun groupOf(split: S, file: F): GroupInstance<F>? = bySplit[split]?.firstOrNull { file in it }

    /** The splits that have at least one instance. */
    fun splits(): Set<S> = bySplit.keys.toSet()

    /** A new group containing [file] in [split]. The file first leaves its current group there. */
    fun createGroup(split: S, file: F, color: GroupColor, name: String = ""): TabGroup {
        val group = TabGroup(newId(), name, color)
        groupMap[group.id] = group
        val touched = detach(split, file, except = null)
        list(split).add(GroupInstance(group.id, listOf(file), false))
        prune(touched)
        return group
    }

    /**
     * A new, empty group with an empty instance in [split] (appended). It is kept while empty ([TabGroup.keepWhileEmpty]) until
     * it gets a member.
     */
    fun createEmptyGroup(split: S, color: GroupColor, name: String = ""): TabGroup {
        val group = TabGroup(newId(), name, color)
        group.keepWhileEmpty = true
        groupMap[group.id] = group
        list(split).add(GroupInstance(group.id, emptyList(), false))
        return group
    }

    /**
     * [file] becomes the last member of group [groupId] in [split], creating the instance if the split has none. It leaves any
     * other group there. A no-op if it already is the last member; ignored if the group is unknown.
     */
    fun addToGroup(split: S, groupId: String, file: F) {
        val group = groupMap[groupId] ?: return
        val target = instance(split, groupId)
        if (target?.members?.lastOrNull() == file) return
        group.keepWhileEmpty = false
        val touched = detach(split, file, except = target)
        if (target != null) {
            target.remove(file)
            target.add(file)
        } else {
            list(split).add(GroupInstance(groupId, listOf(file), false))
        }
        prune(touched)
    }

    /** A tab is removed, closed or pinned: [file] leaves its group in [split]. */
    fun removeFromGroup(split: S, file: F) {
        prune(detach(split, file, except = null))
    }

    /**
     * Puts the instances of [split] in the order of [groupIds] (the order of their headers in the strip). Instances not listed keep
     * their relative order after the listed ones; unknown ids are ignored. Membership is unchanged.
     */
    fun reorderInstances(split: S, groupIds: List<String>) {
        val instances = bySplit[split] ?: return
        val rank = groupIds.withIndex().associate { it.value to it.index }
        instances.sortBy { rank[it.groupId] ?: Int.MAX_VALUE }
    }

    /** Collapses or expands the instance fully (a partial expand ends either way). */
    fun setCollapsed(split: S, groupId: String, collapsed: Boolean) {
        instance(split, groupId)?.collapsed = collapsed
    }

    /**
     * Partial expand: the collapsed instance of [groupId] in [split] shows [file] beside its header and keeps its other tabs hidden.
     * False (nothing changed) if there is no such instance, it is expanded, or [file] is not one of its members.
     */
    fun showPartially(split: S, groupId: String, file: F): Boolean {
        val instance = instance(split, groupId) ?: return false
        if (!instance.collapsed || file !in instance) return false
        instance.peek = file
        return true
    }

    /**
     * Drag results: [layout] is the complete new set of instances of [split], in order. Unknown groups are ignored, instances missing
     * from it are removed, a group listed twice keeps its first entry, empty instances go through the empty-instance cleanup and a file stays in at most one instance (first
     * occurrence of a file wins). Names and colours are never taken from here. [keepEmpty]: groups whose empty instance stays although
     * the cleanup would remove it (the group's only tab was dragged out and the user is asked); they get
     * [TabGroup.keepWhileEmpty] provisionally. An instance that was empty before this result and is listed empty again stays as it is
     * (the drag did not empty it).
     */
    fun replaceSplit(split: S, layout: List<InstanceLayout<F>>) = replaceSplit(split, layout, emptySet())

    /** [replaceSplit] with [keepEmpty]: see above. */
    fun replaceSplit(split: S, layout: List<InstanceLayout<F>>, keepEmpty: Collection<String>) {
        val before = bySplit[split]?.map { it.groupId }.orEmpty()
        // an instance that was empty already is not something this result emptied: it stays as it was (moving
        // a group out of a split must not take that split's empty groups with it)
        val emptyBefore = bySplit[split].orEmpty().filter { it.members.isEmpty() }.mapTo(HashSet()) { it.groupId }
        val seenFiles = HashSet<F>()
        val seenGroups = HashSet<String>()
        val result = ArrayList<GroupInstance<F>>()
        for (item in layout) {
            val group = groupMap[item.groupId] ?: continue
            if (!seenGroups.add(item.groupId)) continue
            val members = item.members.filter { seenFiles.add(it) }
            if (members.isNotEmpty()) group.keepWhileEmpty = false
            else if (item.groupId in keepEmpty) group.keepWhileEmpty = true
            if (members.isEmpty() && !keepWhenEmpty(group) && item.groupId !in emptyBefore) continue
            val instance = GroupInstance(item.groupId, members, item.collapsed)
            item.peek?.takeIf { item.collapsed && it in members }?.let { instance.peek = it } // a partial expand survives a drag result
            result.add(instance)
        }
        if (result.isEmpty()) bySplit.remove(split) else bySplit[split] = result
        // empty-instance and identity cleanup for what this result changed; empty instances it left untouched are not pruned
        val untouched = result.filter { it.members.isEmpty() && it.groupId in emptyBefore }.mapTo(HashSet()) { it.groupId }
        prune(before.filter { it !in untouched })
    }

    /**
     * A whole instance moved in from another split (or brought back from the stash, a record or a stored state). Members are appended,
     * de-duplicated, to this split's instance of the group, or become a new one. A live identity keeps everything it has ([rule]
     * included). If the group identity no longer exists (it was removed when the source instance went away) it is revived if it was
     * removed recently (same object, so its name, colour and rule are kept), else re-created with the given [id], [name] and [color];
     * a non-null [rule] then becomes its rule. The rule is set before the empty-instance cleanup, so an empty instance of a group
     * with an enabled rule stays. [keepEmpty] (an instance that was stored empty because the user had kept it): with no
     * members the group gets [TabGroup.keepWhileEmpty] before that cleanup, so the empty instance stays.
     */
    fun acceptInstance(
        split: S,
        id: String,
        name: String,
        color: GroupColor,
        members: List<F>,
        collapsed: Boolean,
        rule: AutoGroupRule? = null,
        keepEmpty: Boolean = false,
    ): GroupInstance<F> {
        if (groupMap[id] == null) {
            val group = forgotten.remove(id) ?: TabGroup(id, name, color)
            if (rule != null) group.rule = rule
            groupMap[id] = group
        }
        var target = instance(split, id)
        val touched = HashSet<String>()
        for (file in members) touched += detach(split, file, except = target)
        if (target == null) {
            target = GroupInstance(id, emptyList(), collapsed)
            list(split).add(target)
        }
        for (file in members) if (file !in target) target.add(file)
        if (members.isNotEmpty()) groupMap[id]?.keepWhileEmpty = false
        else if (keepEmpty) groupMap[id]?.keepWhileEmpty = true
        prune(touched + id)
        return target
    }

    /**
     * Removes [split]'s instance of [groupId], whatever its members. Other splits keep theirs. The group identity goes
     * with the last instance. A no-op for an unknown instance.
     */
    fun removeInstance(split: S, groupId: String) {
        val instances = bySplit[split] ?: return
        if (!instances.removeAll { it.groupId == groupId }) return
        prune(listOf(groupId))
    }

    /**
     * The empty-instance and identity cleanup for [groupId] right now: its empty instances go unless it still has an enabled rule, and the group with
     * its last instance. For a group whose rule was just disabled or removed.
     */
    fun pruneEmpty(groupId: String) = prune(listOf(groupId))

    /** A file or directory moved from [oldUrl] to [newUrl]: the directory rules of the groups follow. True if any rule changed. */
    fun rewriteRuleUrls(oldUrl: String, newUrl: String): Boolean {
        var changed = false
        for (group in groupMap.values + forgotten.values) {
            val rule = group.rule ?: continue
            val moved = rule.withUrlMoved(oldUrl, newUrl)
            if (moved !== rule) {
                group.rule = moved
                changed = true
            }
        }
        return changed
    }

    /** The split no longer exists: all its instances go and the identity cleanup applies. */
    fun splitRemoved(split: S) {
        val gone = bySplit.remove(split) ?: return
        prune(gone.map { it.groupId })
    }

    private fun list(split: S) = bySplit.getOrPut(split) { ArrayList() }

    /** Removes [file] from every instance of [split] except [except]; returns the group ids touched, for [prune]. */
    private fun detach(split: S, file: F, except: GroupInstance<F>?): Set<String> {
        val touched = HashSet<String>()
        for (inst in bySplit[split].orEmpty()) if (inst !== except && inst.remove(file)) touched += inst.groupId
        return touched
    }

    /** The empty-instance cleanup for [groupIds], then the identity cleanup for those groups if they are left without instances. */
    private fun prune(groupIds: Collection<String>) {
        val ids = groupIds.toSet()
        for (id in ids) {
            val group = groupMap[id] ?: continue
            if (keepWhenEmpty(group)) continue
            for (instances in bySplit.values) instances.removeAll { it.groupId == id && it.members.isEmpty() }
        }
        bySplit.values.removeAll { it.isEmpty() }
        for (id in ids) {
            val group = groupMap[id] ?: continue
            if (bySplit.values.none { l -> l.any { it.groupId == id } }) forget(groupMap.remove(id) ?: continue)
        }
    }

    private fun forget(group: TabGroup) {
        forgotten.remove(group.id)
        forgotten[group.id] = group
        while (forgotten.size > FORGOTTEN_MAX) forgotten.remove(forgotten.keys.first())
    }

    private companion object {
        const val FORGOTTEN_MAX = 64
    }
}
