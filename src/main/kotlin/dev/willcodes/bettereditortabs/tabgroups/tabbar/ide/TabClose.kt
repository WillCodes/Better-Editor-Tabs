package dev.willcodes.bettereditortabs.tabgroups.tabbar.ide

import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals

/**
 * Closing tabs on behalf of group operations.
 *
 * Internal APIs wrapped, and why:
 * - `EditorWindow.closeFile`: closes a tab and disposes the split if it was the last one.
 * - the history behind Reopen Closed Tab ([PlatformInternals.removedTabs]): `closeFile` always appends to it and has no flag to skip
 *   that, but moving a group to another split is not a close the user should be able to undo in the source split, so [closeForMove]
 *   removes the entry it just created. If the field cannot be resolved (platform change), a warning is logged once and the entry
 *   stays, which is only cosmetic.
 */
internal object TabClose {
    /** The subset of [files] that is open in [window] (hidden tabs of collapsed groups included), in the given order. */
    fun openTabs(window: EditorWindow, files: List<VirtualFile>): List<VirtualFile> =
        files.filter { window.getComposite(it) != null }

    /** Closes [files] (those open in [window]) the normal way: each one is added to Reopen Closed Tab history. */
    fun closeTabs(window: EditorWindow, files: List<VirtualFile>) {
        for (file in files) {
            if (window.isDisposed) return
            if (window.getComposite(file) != null) window.closeFile(file, true)
        }
    }

    /**
     * Closes [file] in [window] as part of moving it elsewhere, without leaving it in the window's Reopen Closed Tab history.
     * Only the entry created by this very call is removed; entries of earlier closes are never touched.
     */
    fun closeForMove(window: EditorWindow, file: VirtualFile, disposeIfNeeded: Boolean) {
        val list = PlatformInternals.removedTabs(window)
        val lastBefore = list?.lastOrNull()
        // The window may be disposed by closeFile (disposeIfNeeded), but the deque stays reachable through the field.
        window.closeFile(file, disposeIfNeeded)
        if (list == null) return
        val last = list.lastOrNull()
        if (last != null && last !== lastBefore && PlatformInternals.removedTabFile(last) == file) {
            list.removeAt(list.lastIndex)
        }
    }

    /**
     * Removes the newest Reopen Closed Tab entry of [window] if it is the one of [file] (a placeholder of a temporary split that was
     * closed by something other than us: reopening it would bring the in-memory file back). Fail soft like [closeForMove].
     */
    fun dropEntryOf(window: EditorWindow, file: VirtualFile) {
        val list = PlatformInternals.removedTabs(window) ?: return
        val last = list.lastOrNull() ?: return
        if (PlatformInternals.removedTabFile(last) == file) list.removeAt(list.lastIndex)
    }

    /** Number of Reopen Closed Tab entries of [window], or null if the platform field can't be read. Used by tests. */
    fun historySize(window: EditorWindow): Int? = PlatformInternals.removedTabs(window)?.size
}
