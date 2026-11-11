package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Registered as a project listener in `plugin.xml`: keeps [TabBar] aware of new and closed splits (it discovers them from
 * `FileEditorManagerEx.windows`) and reports closed files for MEM-1 and pinned files for MEM-3. Does nothing while no host is attached.
 */
class TabBarEditorListener(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) = project.getService(TabBar::class.java).sync()
    override fun fileClosed(source: FileEditorManager, file: VirtualFile) = project.getService(TabBar::class.java).fileClosed(file)
    override fun filePinStateChanged(source: FileEditorManager, file: VirtualFile) = project.getService(TabBar::class.java).filePinStateChanged(file)
    override fun selectionChanged(event: FileEditorManagerEvent) = project.getService(TabBar::class.java).sync()
}

/** Initial discovery of the splits restored at startup; also creates the [TabBar] service, which runs the platform self-check once. */
class TabBarStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        withContext(Dispatchers.EDT) {
            project.getService(TabBar::class.java).sync()
        }
    }
}
