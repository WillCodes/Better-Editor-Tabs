package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.impl.JBEditorTabs

/**
 * Keeps the platform from picking a hidden (collapsed) tab as the next selection when the selected tab is closed.
 *
 * `EditorWindow.computeIndexToSelect` can pick a hidden tab in the "activate right editor" and "activate most recently used editor"
 * close modes, which ends in `LOG.error("Cannot find tab for selection transfer")`. If that would happen, a visible tab is selected
 * first; the platform then sees a non-selected tab being closed and keeps that selection.
 *
 * Internal APIs wrapped: `EditorWindow.setSelectedComposite`, `EditorHistoryManager.fileList`, `UISettingsState` close modes, `JBTabsImpl.tabs`.
 * EDT only.
 */
internal class CloseSelectionGuard(
    private val tabs: JBEditorTabs,
    private val window: EditorWindow,
    private val collapse: CollapseController,
) {
    /**
     * Called before [file] is closed. Reads the two close modes of `UISettings` and the editor history (`EditorHistoryManager`, oldest
     * first) only when a hidden tab could be picked.
     */
    fun beforeFileClosed(file: VirtualFile) {
        if (!collapse.hasHiddenFiles() || StripReader.selectedFile(tabs) != file) return
        val state = UISettings.getInstance().state
        val activeRight = state.activeRightEditorOnClose
        val activeMru = state.activeMruEditorOnClose
        val recentFiles = EditorHistoryManager.getInstance(window.manager.project).fileList
        val order = StripReader.visibleFiles(tabs)
        val index = order.indexOf(file)
        if (index < 0 || order.size < 2) return
        val target: VirtualFile? = when {
            activeMru -> {
                val first = recentFiles.asReversed().firstOrNull { it != file && StripReader.infoOf(tabs, it) != null }
                if (first != null && collapse.isHidden(first)) recentFiles.asReversed().firstOrNull { it != file && it in order } else null
            }
            activeRight -> if (index + 1 >= order.size) order[index - 1] else null
            else -> null
        }
        if (target != null) window.setSelectedComposite(target, false)
    }
}
