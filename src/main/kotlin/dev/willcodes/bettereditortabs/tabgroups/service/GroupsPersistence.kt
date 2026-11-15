package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.groups.StashEntry
import dev.willcodes.bettereditortabs.tabgroups.model.persist.colorFromName
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredGroup
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredGroups
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredInstance
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredRule
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredSplit
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredStash
import dev.willcodes.bettereditortabs.tabgroups.model.persist.toStored
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.SplitInfo

/**
 * The XML form of the project's groups in the workspace file (PERS-1): `<component name="BetterEditorTabsGroups">`. Plain beans with
 * defaults, so the serializer leaves out what is empty or false. The stash (PERS-4) is [stash]; a group's auto-group rule (RULE-1) is
 * three attributes of its bean ([GroupBean.rule] ...). The serializer ignores elements it does not know and keeps the defaults of
 * those missing, so state written by another version stays readable (PERS-4).
 *
 * Never mutated after it has been handed to the platform: [TabGroupsService.getState] may be read on another thread while the saved
 * state is serialized, so every change builds a new bean.
 */
class GroupsStateBean {
    @XCollection(propertyElementName = "groups")
    var groups: MutableList<GroupBean> = ArrayList()

    @XCollection(propertyElementName = "splits")
    var splits: MutableList<SplitBean> = ArrayList()

    /** PERS-4: the stashed groups, newest first. */
    @XCollection(propertyElementName = "stash")
    var stash: MutableList<StashBean> = ArrayList()
}

@Tag("stashed")
class StashBean {
    @Attribute
    var id: String = ""

    /** The group identity, so a restore can re-link it. */
    @Attribute
    var group: String = ""

    @Attribute
    var name: String = ""

    /** A [dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor] name. */
    @Attribute
    var color: String = ""

    /** Epoch milliseconds of the Stash Group. */
    @Attribute
    var at: Long = 0

    @XCollection(propertyElementName = "files", elementName = "f", valueAttributeName = "url")
    var files: MutableList<String> = ArrayList()

    /** The group's rule then: a [dev.willcodes.bettereditortabs.tabgroups.model.groups.RuleKind] name, "" for none (RULE-1). */
    @Attribute
    var rule: String = ""

    /** The rule's value: module name, directory URL or pattern. */
    @Attribute
    var ruleValue: String = ""

    /** Whether the rule is enabled. */
    @Attribute
    var ruleOn: Boolean = false
}

@Tag("group")
class GroupBean {
    @Attribute
    var id: String = ""

    @Attribute
    var name: String = ""

    /** A [dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupColor] name. */
    @Attribute
    var color: String = ""

    /** The group's rule (RULE-1): a [dev.willcodes.bettereditortabs.tabgroups.model.groups.RuleKind] name, "" for none. */
    @Attribute
    var rule: String = ""

    /** The rule's value: module name, directory URL (rewritten on moves, MEM-4) or pattern. */
    @Attribute
    var ruleValue: String = ""

    /** Whether the rule is enabled. */
    @Attribute
    var ruleOn: Boolean = false
}

@Tag("split")
class SplitBean {
    /** The encoded `SplitKey`. */
    @Attribute
    var key: String = ""

    /** The whole logical tab order of the split as VFS URLs. */
    @XCollection(propertyElementName = "order", elementName = "f", valueAttributeName = "url")
    var order: MutableList<String> = ArrayList()

    @XCollection(propertyElementName = "instances")
    var instances: MutableList<InstanceBean> = ArrayList()

    /** IDE-2: the manual order of a split the plugin sorts alphabetically, as VFS URLs; empty otherwise. */
    @XCollection(propertyElementName = "manualOrder", elementName = "f", valueAttributeName = "url")
    var manualOrder: MutableList<String> = ArrayList()
}

@Tag("instance")
class InstanceBean {
    @Attribute
    var group: String = ""

    @Attribute
    var collapsed: Boolean = false

    /** PERS-3 amended: for an instance stored empty, the number of logical tabs in front of its pill; -1 (not written) otherwise. */
    @Attribute
    var place: Int = -1

    @XCollection(propertyElementName = "members", elementName = "f", valueAttributeName = "url")
    var members: MutableList<String> = ArrayList()
}

/** Conversions between the live model, the stored data ([StoredGroups], pure) and the XML beans. */
internal object GroupsPersistence {
    fun toStored(bean: GroupsStateBean): StoredGroups = StoredGroups(
        groups = bean.groups.map { StoredGroup(it.id, it.name, it.color, ruleOf(it.rule, it.ruleValue, it.ruleOn)) },
        splits = bean.splits.map { s ->
            StoredSplit(
                s.key, s.order.toList(), s.instances.map { StoredInstance(it.group, it.members.toList(), it.collapsed, it.place.takeIf { p -> p >= 0 && it.members.isEmpty() }) }, s.manualOrder.toList(),
            )
        },
        stash = bean.stash.map { StoredStash(it.id, it.group, it.name, it.color, it.files.toList(), it.at, ruleOf(it.rule, it.ruleValue, it.ruleOn)) },
    )

    private fun ruleOf(kind: String, value: String, on: Boolean): StoredRule? = if (kind.isEmpty()) null else StoredRule(kind, value, on)

    fun stashToStored(entries: List<StashEntry>): List<StoredStash> =
        entries.map { StoredStash(it.id, it.groupId, it.name, it.color.name, it.files, it.stashedAt, it.rule?.toStored()) }

    fun stashFromStored(stored: List<StoredStash>): List<StashEntry> =
        stored.map { StashEntry(it.id, it.groupId, it.name, colorFromName(it.color), it.files, it.stashedAt, it.rule?.toRule()) }

    fun toBean(stored: StoredGroups): GroupsStateBean {
        val bean = GroupsStateBean()
        for (g in stored.groups) {
            bean.groups += GroupBean().also {
                it.id = g.id; it.name = g.name; it.color = g.color
                g.rule?.let { r -> it.rule = r.kind; it.ruleValue = r.value; it.ruleOn = r.enabled }
            }
        }
        for (s in stored.splits) {
            bean.splits += SplitBean().also { sb ->
                sb.key = s.key
                sb.order = s.order.toMutableList()
                sb.manualOrder = s.manualOrder.toMutableList()
                sb.instances = s.instances.mapTo(ArrayList()) { i ->
                    InstanceBean().also { it.group = i.groupId; it.collapsed = i.collapsed; it.members = i.members.toMutableList(); it.place = i.place ?: -1 }
                }
            }
        }
        for (e in stored.stash) {
            bean.stash += StashBean().also {
                it.id = e.id; it.group = e.groupId; it.name = e.name; it.color = e.color; it.at = e.stashedAt; it.files = e.files.toMutableList()
                e.rule?.let { r -> it.rule = r.kind; it.ruleValue = r.value; it.ruleOn = r.enabled }
            }
        }
        return bean
    }

    /**
     * The stored form of the live [state]: every split in [infos] that has a group instance, with its key, its logical tab order
     * ([logicalOrder], PERS-1), its instances and, while the plugin sorts it alphabetically, its manual order ([manualOrder], IDE-2).
     * Members whose file is no longer valid (deleted, MEM-4) are left out; identities that no stored instance uses are not stored (the
     * stash keeps its own copy of a group's name and colour, [StoredStash]). The result has no stash: [SavedGroups] adds it.
     */
    fun capture(
        state: GroupsState<SplitHandle, VirtualFile>,
        infos: List<SplitInfo>,
        logicalOrder: (SplitHandle) -> List<VirtualFile>,
        manualOrder: (SplitHandle) -> List<VirtualFile>? = { null },
        headerIndex: (SplitHandle, String) -> Int? = { _, _ -> null },
    ): StoredGroups {
        val splits = ArrayList<StoredSplit>()
        val used = HashSet<String>()
        for (info in infos) {
            val order = logicalOrder(info.handle).filter { it.isValid }.map { it.url }
            val instances = state.instancesIn(info.handle).mapNotNull { inst ->
                val members = inst.members.filter { it.isValid }
                when {
                    members.isNotEmpty() -> StoredInstance(inst.groupId, members.map { it.url }, inst.collapsed)
                    // PERS-3 amended: an instance that is empty in the live state (kept or ruled) is stored empty, with its place.
                    inst.members.isEmpty() ->
                        StoredInstance(inst.groupId, emptyList(), inst.collapsed, (headerIndex(info.handle, inst.groupId) ?: order.size).coerceIn(0, order.size))
                    else -> null // its files were all deleted (MEM-4)
                }
            }
            if (instances.isEmpty()) continue
            used += instances.map { it.groupId }
            val manual = manualOrder(info.handle).orEmpty().filter { it.isValid }.map { it.url }
            splits += StoredSplit(info.key.encode(), order, instances, manual)
        }
        val groups = state.groups.filter { it.id in used }.map { StoredGroup(it.id, it.name, it.color.name, it.rule?.toStored()) }
        return StoredGroups(groups, splits)
    }
}
