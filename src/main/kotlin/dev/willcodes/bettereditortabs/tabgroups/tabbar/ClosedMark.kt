package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.fileEditor.impl.EditorWindow

/**
 * Opaque marker of "the close that [TabBar.closeTabsQuietly] did": remembers the split and the top of its Reopen Closed Tab history
 * right after that close. [TabBar.isMostRecentClose] tells whether nothing has been closed or reopened there since (LIFE-5). Only
 * `tabbar/` creates marks.
 */
class ClosedMark internal constructor(internal val window: EditorWindow, internal val historyTop: Any?, internal val historyReadable: Boolean)

/**
 * The result of [TabBar.openInOrder]: the split the files were opened in (the platform makes one if there was none) and the files of
 * the request that have a tab there afterwards, in the order requested.
 */
class OpenedBlock internal constructor(val split: SplitHandle, val files: List<com.intellij.openapi.vfs.VirtualFile>)
