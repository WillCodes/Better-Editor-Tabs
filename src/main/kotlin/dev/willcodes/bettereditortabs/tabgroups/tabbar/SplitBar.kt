package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.tabbar.drag.SplitGroups
import dev.willcodes.bettereditortabs.tabgroups.tabbar.drag.TabBarDnd
import dev.willcodes.bettereditortabs.tabgroups.tabbar.ide.TabPlacement
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.GroupHeader
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripEvents
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.TabBarAdapter
import java.awt.event.MouseEvent

/**
 * Everything the tab bar keeps for one split while a host is attached: the strip adapter, the drag layer (tab and group drag and drop)
 * and MEM-2 placement, plus the groups last applied to the split and an immutable index of them for lookups from any thread.
 * Created by [TabBar] when a split appears, disposed with it.
 *
 * Bridges the sub-packages to the host: strip events and drag results come in through [StripEvents] and [SplitGroups] and leave through
 * [TabBarHost]. EDT only, except [groupIdOf].
 */
internal class SplitBar(val split: SplitHandle, private val tabBar: TabBar, parent: Disposable) : Disposable, StripEvents, SplitGroups {
    val adapter = TabBarAdapter(split.window, this)

    /** The groups last applied to the split (what the strip shows). */
    @Volatile
    var groups: List<GroupDecoration> = emptyList()
        private set

    /** File -> id of its group, built from [groups]; swapped as a whole so that any thread may read it. */
    @Volatile
    private var index: Map<VirtualFile, String> = emptyMap()

    val dnd = TabBarDnd(adapter, this) { tabBar.dragLayers() }

    init {
        Disposer.register(parent, this)
        TabPlacement(adapter, { groups }, { dnd.isTabDragActive }, dnd)
        adapter.events = this
    }

    /** Applies [newGroups] to the strip and refreshes the index. */
    fun apply(newGroups: List<GroupDecoration>) {
        groups = newGroups
        val map = HashMap<VirtualFile, String>()
        for (g in newGroups) for (f in g.members) map.putIfAbsent(f, g.id)
        index = map
        adapter.setGroups(newGroups)
    }

    /** The group [file] belongs to in this split, per the groups last applied; safe to call from any thread. */
    fun groupIdOf(file: VirtualFile): String? = index[file]

    // ---- StripEvents -----------------------------------------------------------------------------------------------------

    private fun ref(header: GroupHeader) = HeaderRef(split, header.groupId, header)

    override fun headerClicked(header: GroupHeader, event: MouseEvent) {
        tabBar.host?.headerClicked(ref(header), event)
    }

    override fun headerRightClicked(header: GroupHeader, event: MouseEvent) {
        tabBar.host?.headerRightClicked(ref(header), event)
    }

    override fun headerMiddleClicked(header: GroupHeader, event: MouseEvent) {
        tabBar.host?.headerMiddleClicked(ref(header), event)
    }

    override fun hiddenTabSelected(groupId: String, file: VirtualFile) {
        tabBar.host?.hiddenTabSelected(split, groupId, file)
    }

    // ---- SplitGroups -----------------------------------------------------------------------------------------------------

    override fun groups(): List<GroupDecoration> = groups

    override fun commit(groups: List<GroupDecoration>) {
        tabBar.host?.groupsChangedByDrag(split, groups)
        tabBar.refresh(split)
    }

    override fun acceptGroup(group: GroupDecoration) {
        tabBar.host?.groupMovedIn(split, group)
        tabBar.refresh(split)
    }

    override fun dispose() {
    }
}
