package dev.willcodes.bettereditortabs.tabgroups.tabbar.ide

import com.intellij.ide.IdeBundle
import com.intellij.ide.actions.CloseAllEditorsAction
import com.intellij.ide.actions.CloseAllEditorsButActiveAction
import com.intellij.ide.actions.CloseAllEditorsToTheLeftAction
import com.intellij.ide.actions.CloseEditorsActionBase
import com.intellij.idea.ActionsBundle
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorComposite
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.CloseScope
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.isPlaceholder

private typealias CloseCandidates = List<Pair<EditorComposite, EditorWindow>>

/**
 * Applies the scoping rule ([CloseScope]) to the candidates of a built-in close action, while a host is attached.
 *
 * Wraps internal API: `EditorWindow`, `EditorComposite`, `EditorWindow.getContextFile()` (the right-clicked tab, else the
 * selected tab) and `FileEditorManagerEx.closeFilesWithChecks` (the single place every bulk close goes through).
 */
internal object CloseScoping {

    /** The tab bar service if a host is attached (the plugin is active), else null: then the built-in behaviour runs unchanged. */
    private fun activeTabBar(project: Project): TabBar? = project.serviceIfCreated<TabBar>()?.takeIf { it.host != null }

    /** True if the scoping applies to [e] (a host is attached). When false the actions behave exactly like the built-in ones. */
    fun isActive(e: AnActionEvent): Boolean = e.project?.let { activeTabBar(it) } != null

    fun scope(e: AnActionEvent, candidates: CloseCandidates): CloseCandidates {
        val project = e.project ?: return candidates
        val tabBar = activeTabBar(project)
        if (tabBar == null || candidates.isEmpty()) return candidates
        val contextGroup = contextGroupId(e, project, tabBar)
        val scoped = CloseScope.filter(contextGroup, candidates) { tabBar.groupIdOf(SplitHandle(it.second), it.first.file) }
        return withoutPlaceholders(scoped)
    }

    /**
     * The placeholder of a temporary split is not a tab of the user's; no close action takes it along with real tabs (it
     * closes by itself when a real tab is there). A split that holds nothing else is closed by an action that names it, which is the
     * user closing the temporary split (its groups move to another split, `TabBarHost.temporarySplitClosing`).
     */
    private fun withoutPlaceholders(candidates: CloseCandidates): CloseCandidates {
        val withReal = candidates.filter { !isPlaceholder(it.first.file) }.mapTo(HashSet()) { it.second }
        return candidates.filter { !isPlaceholder(it.first.file) || it.second !in withReal }
    }

    /** The window the action works on: the one in the data context (editor/tab popup), else the current window (main menu, shortcut). */
    fun contextWindow(e: AnActionEvent, project: Project): EditorWindow? =
        e.getData(EditorWindow.DATA_KEY) ?: FileEditorManagerEx.getInstanceEx(project).currentWindow

    /** The context tab: the right-clicked tab, or the selected tab when there is no popup. */
    fun contextFile(e: AnActionEvent, project: Project): VirtualFile? {
        val window = contextWindow(e, project) ?: return null
        return window.getContextFile() ?: window.selectedFile
    }

    private fun contextGroupId(e: AnActionEvent, project: Project, tabBar: TabBar): String? {
        val window = contextWindow(e, project) ?: return null
        val file = contextFile(e, project) ?: return null
        return tabBar.groupIdOf(SplitHandle(window), file)
    }

    fun pairs(window: EditorWindow, files: Iterable<VirtualFile>): CloseCandidates =
        files.mapNotNull { file -> window.getComposite(file)?.let { Pair.create(it, window) } }
}

/** Close All Tabs (`CloseAllEditors`). Copy of the built-in selection logic plus the scoping filter. */
class CloseAllAction : CloseAllEditorsAction() {
    init {
        templatePresentation.setText(ActionsBundle.actionText("CloseAllEditors"))
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (!CloseScoping.isActive(e)) return super.actionPerformed(e)
        val project = e.project ?: return
        CommandProcessor.getInstance().executeCommand(project, {
            val manager = FileEditorManagerEx.getInstanceEx(project)
            val window = e.getData(EditorWindow.DATA_KEY)
            val candidates: CloseCandidates = if (window != null) {
                CloseScoping.pairs(window, window.fileList)
            } else {
                val selected = manager.selectedFiles.firstOrNull() ?: return@executeCommand
                manager.windows.flatMap { CloseScoping.pairs(it, manager.getSiblings(selected)) }
            }
            manager.closeFilesWithChecks(CloseScoping.scope(e, candidates))
        }, IdeBundle.message("command.close.all.editors"), null)
    }
}

/** Close Other Tabs (`CloseAllEditorsButActive`). Copy of the built-in selection logic plus the scoping filter. */
class CloseOthersAction : CloseAllEditorsButActiveAction() {
    init {
        templatePresentation.setText(ActionsBundle.actionText("CloseAllEditorsButActive"))
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (!CloseScoping.isActive(e)) return super.actionPerformed(e)
        val project = e.project ?: return
        val manager = FileEditorManagerEx.getInstanceEx(project)
        val keep = CloseScoping.contextFile(e, project) ?: return
        val window = e.getData(EditorWindow.DATA_KEY)
        val candidates: CloseCandidates = if (window != null) {
            CloseScoping.pairs(window, window.fileList.filter { it != keep && !window.isFilePinned(it) })
        } else {
            manager.windows.flatMap { w -> CloseScoping.pairs(w, manager.getSiblings(keep).filter { it != keep }) }
        }
        manager.closeFilesWithChecks(CloseScoping.scope(e, candidates))
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.text = ActionsBundle.actionText("CloseAllEditorsButActive")
    }
}

/**
 * Close Tabs to the Left (`CloseAllToTheLeft`). Subclasses the built-in action, so the candidate logic is inherited;
 * `getFilesToClose` (also used for enablement) is narrowed by the scoping filter.
 */
open class CloseLeftAction : CloseAllEditorsToTheLeftAction() {
    override fun getFilesToClose(event: AnActionEvent): CloseCandidates = CloseScoping.scope(event, super.getFilesToClose(event))
}

/** Close Tabs to the Right (`CloseAllToTheRight`). The built-in class is final, so it reuses the Left class with the mirrored predicate. */
class CloseRightAction : CloseLeftAction() {
    override fun isOKToClose(contextFile: VirtualFile?, candidateFile: VirtualFile?, cursorFile: VirtualFile?): Boolean = cursorFile == contextFile

    override fun getAlternativeTextKey(): String = "action.close.all.editors.below"

    override fun getPresentationText(inSplitter: Boolean): String = ActionsBundle.actionText("CloseAllToTheRight")
}

/** Close Unmodified Tabs (`CloseAllUnmodifiedEditors`, registered by the VCS module). The built-in class is final and package-private. */
class CloseUnmodifiedAction : CloseEditorsActionBase() {
    override fun getFilesToClose(event: AnActionEvent): CloseCandidates = CloseScoping.scope(event, super.getFilesToClose(event))

    override fun isFileToClose(editor: EditorComposite, window: EditorWindow, fileEditorManager: FileEditorManagerEx): Boolean =
        !fileEditorManager.isChanged(editor) && !window.isFilePinned(editor.file)

    override fun isActionEnabled(project: Project, event: AnActionEvent): Boolean =
        super.isActionEnabled(project, event) && ProjectLevelVcsManager.getInstance(project).getAllActiveVcss().isNotEmpty()

    override fun getPresentationText(inSplitter: Boolean): String =
        if (inSplitter) IdeBundle.message("action.close.all.unmodified.editors.in.tab.group")
        else IdeBundle.message("action.close.all.unmodified.editors")
}

/** Close All but Pinned (`CloseAllUnpinnedEditors`). The built-in class is final and package-private. */
class CloseUnpinnedAction : CloseEditorsActionBase() {
    override fun getFilesToClose(event: AnActionEvent): CloseCandidates = CloseScoping.scope(event, super.getFilesToClose(event))

    override fun isFileToClose(editor: EditorComposite, window: EditorWindow, fileEditorManager: FileEditorManagerEx): Boolean =
        !window.isFilePinned(editor.file)

    /** Same enablement as the built-in action: needs at least one unpinned tab, and (outside the context menu) any tab. */
    override fun isActionEnabled(project: Project, event: AnActionEvent): Boolean {
        val toClose = getFilesToClose(event)
        if (toClose.isEmpty()) return false
        var hasPinned = false
        var hasUnpinned = false
        for (window in toClose.mapTo(LinkedHashSet()) { it.second }) {
            for (composite in window.allComposites) {
                if (composite.isPinned) hasPinned = true else hasUnpinned = true
            }
            if ((hasPinned || !event.isFromContextMenu) && hasUnpinned) return true
        }
        return false
    }

    override fun getPresentationText(inSplitter: Boolean): String =
        if (inSplitter) IdeBundle.message("action.close.all.unpinned.editors.in.tab.group")
        else IdeBundle.message("action.close.all.unpinned.editors")
}
