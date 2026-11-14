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
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredSplit
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredStash
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.SplitInfo

/**
 * The XML form of the project's groups in the workspace file (PERS-1): `<component name="BetterEditorTabsGroups">`. Plain beans with
 * defaults, so the serializer leaves out what is empty or false. The stash (Stage 10, PERS-4) is [stash]; rules (Stage 11) will be added
 * as further properties with defaults: the serializer ignores elements it does not know and keeps the defaults of those missing, so state written
 * by this version stays readable and the reverse does not fail either (PERS-4).
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

    @XCollection(propertyElementName = "members", elementName = "f", valueAttributeName = "url")
    var members: MutableList<String> = ArrayList()
}

/** Conversions between the live model, the stored data ([StoredGroups], pure) and the XML beans. */
internal object GroupsPersistence {
    fun toStored(bean: GroupsStateBean): StoredGroups = StoredGroups(
        groups = bean.groups.map { StoredGroup(it.id, it.name, it.color) },
        splits = bean.splits.map { s ->
            StoredSplit(
                s.key, s.order.toList(), s.instances.map { StoredInstance(it.group, it.members.toList(), it.collapsed) }, s.manualOrder.toList(),
            )
        },
        stash = bean.stash.map { StoredStash(it.id, it.group, it.name, it.color, it.files.toList(), it.at) },
    )

    fun stashToStored(entries: List<StashEntry>): List<StoredStash> =
        entries.map { StoredStash(it.id, it.groupId, it.name, it.color.name, it.files, it.stashedAt) }

    fun stashFromStored(stored: List<StoredStash>): List<StashEntry> =
        stored.map { StashEntry(it.id, it.groupId, it.name, colorFromName(it.color), it.files, it.stashedAt) }

    fun toBean(stored: StoredGroups): GroupsStateBean {
        val bean = GroupsStateBean()
        for (g in stored.groups) bean.groups += GroupBean().also { it.id = g.id; it.name = g.name; it.color = g.color }
        for (s in stored.splits) {
            bean.splits += SplitBean().also { sb ->
                sb.key = s.key
                sb.order = s.order.toMutableList()
                sb.manualOrder = s.manualOrder.toMutableList()
                sb.instances = s.instances.mapTo(ArrayList()) { i ->
                    InstanceBean().also { it.group = i.groupId; it.collapsed = i.collapsed; it.members = i.members.toMutableList() }
                }
            }
        }
        for (e in stored.stash) {
            bean.stash += StashBean().also {
                it.id = e.id; it.group = e.groupId; it.name = e.name; it.color = e.color; it.at = e.stashedAt; it.files = e.files.toMutableList()
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
    ): StoredGroups {
        val splits = ArrayList<StoredSplit>()
        val used = HashSet<String>()
        for (info in infos) {
            val instances = state.instancesIn(info.handle).mapNotNull { inst ->
                val members = inst.members.filter { it.isValid }
                if (members.isEmpty()) null else StoredInstance(inst.groupId, members.map { it.url }, inst.collapsed)
            }
            if (instances.isEmpty()) continue
            used += instances.map { it.groupId }
            val manual = manualOrder(info.handle).orEmpty().filter { it.isValid }.map { it.url }
            splits += StoredSplit(info.key.encode(), logicalOrder(info.handle).filter { it.isValid }.map { it.url }, instances, manual)
        }
        val groups = state.groups.filter { it.id in used }.map { StoredGroup(it.id, it.name, it.color.name) }
        return StoredGroups(groups, splits)
    }
}
