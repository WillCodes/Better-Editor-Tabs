package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBEditorTabs
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.isPlaceholder

/**
 * Read-only view of one split's editor tab strip, shared by the strip collaborators and by [dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar]
 * (which answers "what is visible / selected" for splits that have no [TabBarAdapter] yet).
 *
 * Internal APIs wrapped: `EditorWindow.tabbedPane.tabs` (a `JBEditorTabs`), `JBTabsImpl.getVisibleInfos`, `JBTabsImpl.tabs` (all tabs,
 * hidden ones included), `EditorWindow.getContextFile`. EDT only.
 */
internal object StripReader {
    /** The tab component of [window]. */
    fun tabsOf(window: EditorWindow): JBEditorTabs = window.tabbedPane.tabs as JBEditorTabs

    /** Files of the tabs currently shown in the strip, in order. */
    fun visibleFiles(tabs: JBEditorTabs): List<VirtualFile> = tabs.getVisibleInfos().mapNotNull { it.`object` as? VirtualFile }

    /** The selected file, or null if nothing is selected / everything is hidden. */
    fun selectedFile(tabs: JBEditorTabs): VirtualFile? = tabs.selectedInfo?.`object` as? VirtualFile

    /** The right-clicked tab if a tab context menu is showing, otherwise the selected tab. */
    fun contextFile(window: EditorWindow): VirtualFile? = window.getContextFile()

    /** The tab of [file], hidden ones included. */
    fun infoOf(tabs: JBEditorTabs, file: VirtualFile): TabInfo? = tabs.tabs.firstOrNull { it.`object` == file }

    /**
     * Files of all tabs of the strip, hidden ones included, in the platform's order. The placeholder of a temporary split is
     * not a file of the user's and is left out, here and in [openFiles].
     */
    fun allFiles(tabs: JBEditorTabs): List<VirtualFile> = tabs.tabs.mapNotNull { (it.`object` as? VirtualFile)?.takeUnless(::isPlaceholder) }

    /** [allFiles] as a set: the files that have a tab in the strip. */
    fun openFiles(tabs: JBEditorTabs): Set<VirtualFile> = allFiles(tabs).toHashSet()

    /** True if the strip holds the placeholder of a temporary split. */
    fun holdsPlaceholder(tabs: JBEditorTabs): Boolean = tabs.tabs.any { isPlaceholder(it.`object` as? VirtualFile) }
}
