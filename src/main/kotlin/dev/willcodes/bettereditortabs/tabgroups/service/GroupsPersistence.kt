package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import dev.willcodes.bettereditortabs.tabgroups.model.groups.GroupsState
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredGroup
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredGroups
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredInstance
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredSplit
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.SplitInfo

/**
 * The XML form of the project's groups in the workspace file (PERS-1): `<component name="BetterEditorTabsGroups">`. Plain beans with
 * defaults, so the serializer leaves out what is empty or false. The stash (Stage 10) and rules (Stage 11) will be added as further
 * properties with defaults: the serializer ignores elements it does not know and keeps the defaults of those missing, so state written
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
    )

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
        return bean
    }

    /**
     * The stored form of the live [state]: every split in [infos] that has a group instance, with its key, its logical tab order
     * ([logicalOrder], PERS-1), its instances and, while the plugin sorts it alphabetically, its manual order ([manualOrder], IDE-2).
     * Members whose file is no longer valid (deleted, MEM-4) are left out; identities that no stored instance uses are not stored (the
     * stash will add its own, Stage 10).
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
