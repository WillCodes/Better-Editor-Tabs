package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.SplitInfo
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.SplitsRestoredListener

/** PERS-3: the editor layout of the project has settled after startup; the groups are restored (project listener in `plugin.xml`). */
class TabGroupsRestoreListener : SplitsRestoredListener {
    override fun splitsRestored(project: Project, splits: List<SplitInfo>) {
        project.service<TabGroupsService>().restore(splits)
    }
}

/**
 * PERS-1: saves the groups before the project is closed. `ProjectCloseListener.projectClosingBeforeSave` runs on the EDT before the
 * settings are saved and before the editors close (`ProjectManagerImpl.closeProject`); see [TabGroupsService.prepareForClose]. An
 * application listener because the topic is application level and delivers the events of every project.
 */
class TabGroupsCloseListener : ProjectCloseListener {
    override fun projectClosingBeforeSave(project: Project) {
        project.getServiceIfCreated(TabGroupsService::class.java)?.prepareForClose()
    }
}

/**
 * MEM-4: files that are renamed or moved keep their group (their `VirtualFile` stays the same and the platform keeps the tab open),
 * but the stored form names files by URL. Tells every open project's service, on the EDT. The platform closes the tabs of deleted
 * files before the deletion (`FileEditorManagerImpl.MyVirtualFileListener.beforeFileDeletion`), which removes them from their group
 * (MEM-1); afterwards the service drops any member whose file is invalid anyway ([TabGroupsService.dropInvalidMembers]), as that
 * listener is not installed in a headless IDE. Application listener in `plugin.xml`; reaches only services that already exist.
 */
class TabGroupsVfsListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val moves = events.mapNotNull(::moveOf)
        val deleted = events.any { it is VFileDeleteEvent }
        if (moves.isEmpty() && !deleted) return
        val app = ApplicationManager.getApplication()
        for (project in ProjectManager.getInstance().openProjects) {
            val service = project.getServiceIfCreated(TabGroupsService::class.java) ?: continue
            val work = {
                if (moves.isNotEmpty()) service.filesMoved(moves)
                if (deleted) service.dropInvalidMembers()
            }
            if (app.isDispatchThread) work() else app.invokeLater(work, project.disposed)
        }
    }

    private fun moveOf(event: VFileEvent): Pair<String, String>? {
        val (oldPath, newPath) = when {
            event is VFilePropertyChangeEvent && event.propertyName == VirtualFile.PROP_NAME -> event.oldPath to event.newPath
            event is VFileMoveEvent -> event.oldPath to event.newPath
            else -> return null
        }
        val protocol = event.file?.fileSystem?.protocol ?: return null
        return "$protocol://$oldPath" to "$protocol://$newPath"
    }
}
