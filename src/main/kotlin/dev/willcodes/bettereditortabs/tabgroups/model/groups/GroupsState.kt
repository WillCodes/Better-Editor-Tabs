package dev.willcodes.bettereditortabs.tabgroups.model.groups

/**
 * The membership state of all groups (§2): group identities plus, per split [S], the group instances with their member files [F].
 * Pure and generic so it is testable without the platform. Not thread-safe; the caller is on the EDT. Every operation only touches
 * the split it is given, except that dropping the last instance of a group removes the identity (MEM-6).
 *
 * @param newId produces ids for new groups.
 */
class GroupsState<S, F>(private val newId: () -> String) {
    /** RULE-6 hook (Stage 11): true for a group whose empty instances must stay (enabled auto-group rule). MEM-5 skips those. */
    var keepWhenEmpty: (TabGroup) -> Boolean = { false }

    /** Stash hook (Stage 10): true for a stashed group, which keeps existing without instances. MEM-6 skips those. */
    var isStashed: (TabGroup) -> Boolean = { false }

    private val groupMap = LinkedHashMap<String, TabGroup>()
    private val bySplit = LinkedHashMap<S, MutableList<GroupInstance<F>>>()

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

    /** CTX-1: a new group containing [file] in [split]. The file first leaves its current group there (INV-1). */
    fun createGroup(split: S, file: F, color: GroupColor, name: String = ""): TabGroup {
        val group = TabGroup(newId(), name, color)
        groupMap[group.id] = group
        val touched = detach(split, file, except = null)
        list(split).add(GroupInstance(group.id, listOf(file), false))
        prune(touched)
        return group
    }

    /**
     * CTX-2: [file] becomes the last member of group [groupId] in [split], creating the instance if the split has none. It leaves any
     * other group there (INV-1). A no-op if it already is the last member; ignored if the group is unknown.
     */
    fun addToGroup(split: S, groupId: String, file: F) {
        if (groupMap[groupId] == null) return
        val target = instance(split, groupId)
        if (target?.members?.lastOrNull() == file) return
        val touched = detach(split, file, except = target)
        if (target != null) {
            target.remove(file)
            target.add(file)
        } else {
            list(split).add(GroupInstance(groupId, listOf(file), false))
        }
        prune(touched)
    }

    /** CTX-3, MEM-1 (tab closed), MEM-3 (tab pinned): [file] leaves its group in [split]. */
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

    fun setCollapsed(split: S, groupId: String, collapsed: Boolean) {
        instance(split, groupId)?.collapsed = collapsed
    }

    /**
     * Drag results: [layout] is the complete new set of instances of [split], in order. Unknown groups are ignored, instances missing
     * from it are removed, a group listed twice keeps its first entry, empty instances go through MEM-5 and INV-1 holds (first
     * occurrence of a file wins). Names and colours are never taken from here.
     */
    fun replaceSplit(split: S, layout: List<InstanceLayout<F>>) {
        val before = bySplit[split]?.map { it.groupId }.orEmpty()
        val seenFiles = HashSet<F>()
        val seenGroups = HashSet<String>()
        val result = ArrayList<GroupInstance<F>>()
        for (item in layout) {
            val group = groupMap[item.groupId] ?: continue
            if (!seenGroups.add(item.groupId)) continue
            val members = item.members.filter { seenFiles.add(it) }
            if (members.isEmpty() && !keepWhenEmpty(group)) continue
            result.add(GroupInstance(item.groupId, members, item.collapsed))
        }
        if (result.isEmpty()) bySplit.remove(split) else bySplit[split] = result
        prune(before)
    }

    /**
     * A whole instance moved in from another split. Members are appended, de-duplicated, to this split's instance of the group, or
     * become a new one. If the group identity no longer exists (MEM-6 ran when the source instance went away) it is re-created with
     * the given [id], [name] and [color]; an existing identity keeps its own.
     */
    fun acceptInstance(split: S, id: String, name: String, color: GroupColor, members: List<F>, collapsed: Boolean): GroupInstance<F> {
        if (groupMap[id] == null) groupMap[id] = TabGroup(id, name, color)
        var target = instance(split, id)
        val touched = HashSet<String>()
        for (file in members) touched += detach(split, file, except = target)
        if (target == null) {
            target = GroupInstance(id, emptyList(), collapsed)
            list(split).add(target)
        }
        for (file in members) if (file !in target) target.add(file)
        prune(touched + id)
        return target
    }

    /** The split no longer exists: all its instances go and MEM-6 applies. */
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

    /** MEM-5 for the empty instances of [groupIds], then MEM-6 for those groups if they are left without instances. */
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
            if (bySplit.values.none { l -> l.any { it.groupId == id } } && !isStashed(group)) groupMap.remove(id)
        }
    }
}
