package dev.willcodes.bettereditortabs.tabgroups.tabbar.splits

import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileEditor.impl.IdeDocumentHistoryImpl
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle

/**
 * The placeholder tab of a temporary split (SPLIT-6): an in-memory file that keeps a split alive while it holds only empty groups. An
 * `EditorWindow` closes (or is merged away) when its last tab closes, and `EditorWindow.split` / `FileEditorManagerImpl.openFileInNewWindow`
 * need a file, so an empty group header dropped on an editor edge, or a split whose last real tab leaves while an empty group stands in
 * it, would otherwise be impossible or lose the group. The placeholder is opened in that split and hidden at once ([TemporarySplits]).
 *
 * What the platform records for a file and how this one stays out of it (262 sources, checked; the same interfaces exist in 263):
 * - Recent Files, "recently edited": `EditorHistoryManager.isIncludedInHistory` asks `OptionallyIncluded.isIncludedInEditorHistory` first
 *   and otherwise only keeps files that `VirtualFileManager.findFileByUrl(file.url)` finds, which a `LightVirtualFile` (`mock://`) is not;
 *   it also says it is not persisted, so the history of a project never mentions it.
 * - Back / Forward, Recent Locations, last edit: `IdeDocumentHistoryImpl.createPlaceInfo` returns null for a `SkipFromDocumentHistory`
 *   whose `isIncludedInDocumentHistory` is false, and `removeInvalidFilesFrom` drops it from the stacks.
 * - The platform's `FileEditorManagerImpl.selectionHistory` (the Switcher, Ctrl+Tab) and `openFiles` (the Switcher's / Recent Files'
 *   "open files" part) have no filter: while the placeholder is open it can be listed there. Known limit, see [TemporarySplits].
 * - Persisted layout: `EditorsSplitters.writeExternal` writes every tab, but the file cannot be found at the next start (its URL is a
 *   `mock://` one), so it is not reopened ([TemporarySplits.closeAll] closes the placeholders before the project's layout is saved).
 *
 * Both history interfaces are implemented with their methods spelled out: they are Kotlin interfaces with default methods, and a class
 * that left one to the interface would depend on how the platform compiled it.
 */
internal class PlaceholderFile(
    /**
     * True while the split the placeholder was made for is still being built (a group move opens it before the host has heard that
     * the group arrived, and opening a file lets the event queue run): [TemporarySplits] does not end the split for having no group yet.
     * Cleared by [TemporarySplits.created].
     */
    @Volatile var fresh: Boolean = false,
) :
    LightVirtualFile(BetterEditorTabsBundle.message("temporary.split.placeholder"), PlainTextFileType.INSTANCE, ""),
    EditorHistoryManager.OptionallyIncluded,
    IdeDocumentHistoryImpl.SkipFromDocumentHistory {
    init {
        isWritable = false
    }

    override fun isIncludedInEditorHistory(project: Project): Boolean = false

    override fun isPersistedInEditorHistory(): Boolean = false

    override fun isIncludedInDocumentHistory(project: Project): Boolean = false
}

/** True if [file] is the placeholder of a temporary split (SPLIT-6): never a tab of the user's. */
internal fun isPlaceholder(file: VirtualFile?): Boolean = file is PlaceholderFile
