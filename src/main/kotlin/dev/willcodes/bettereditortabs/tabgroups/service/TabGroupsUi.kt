package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef

/**
 * The popups and dialogs [TabGroupsService] needs. The service only knows this interface; `ui/` implements it (`ui.TabGroupsUiImpl`,
 * a project service registered in `plugin.xml` under this interface), so `ui/` depends on `service/` and never the other way round.
 * Tests replace the implementation to answer dialogs headlessly. EDT only.
 */
interface TabGroupsUi {
    /** INT-4: opens the group popup (§6) for the group of [header], next to the header. */
    fun showGroupPopup(header: HeaderRef)

    /**
     * LIFE-3b: asks whether to delete the group shown as [groupName] (its name, or "Unnamed group (Blue)") and close its [tabCount] open
     * tabs in this split, with a "Don't ask again" checkbox. Modal. The service stores the "don't ask again" choice itself.
     */
    fun confirmDelete(groupName: String, tabCount: Int): DeleteAnswer

    /**
     * INT-5: the pointer rests on the header of a collapsed group: after a short delay, show [files] (the group's open tabs, in order)
     * in a list below [header]. Picking an entry calls [TabGroupsService.openFromTabList]. Calling it again for the same header while
     * pending or shown changes nothing.
     */
    fun showTabListOnHover(header: HeaderRef, files: List<VirtualFile>) {}

    /**
     * INT-5: the pointer left [header] (or pressed it): cancels a pending list; a shown list closes unless the pointer moved into it,
     * in which case it closes when the pointer leaves the list.
     */
    fun hoverEnded(header: HeaderRef) {}

    companion object {
        fun getInstance(project: Project): TabGroupsUi = project.service()
    }
}

/** The answer to [TabGroupsUi.confirmDelete]. "Don't ask again" only counts together with Delete. */
enum class DeleteAnswer { DELETE, DELETE_AND_STOP_ASKING, CANCEL }
