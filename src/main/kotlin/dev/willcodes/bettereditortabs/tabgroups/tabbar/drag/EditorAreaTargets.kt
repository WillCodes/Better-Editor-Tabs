package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.ui.tabs.TabsUtil
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EditorAreaDrop
import dev.willcodes.bettereditortabs.tabgroups.model.strip.EditorSide
import dev.willcodes.bettereditortabs.tabgroups.tabbar.stripSide
import java.awt.Point
import java.awt.Rectangle
import javax.swing.SwingUtilities

/**
 * What a group drag needs to know about the editor area of a split, as the platform knows it for a dragged tab
 * (`DockableEditorTabbedContainer.processDropOver`, `JBTabsImpl.processDropOver`, `TabsUtil`): which side of the area the pointer is on
 * and which rectangle the platform highlights for it. The pointer being over a strip is decided before any of this
 * (precedence strip > edge > centre > new window, `GroupDropRules`).
 */
internal class EditorAreaHit(val dnd: TabBarDnd, val side: EditorSide)

/** The whole split (tab row and editor) in screen coordinates, or null while it is not showing (see [TabBarDnd.origin]). */
internal fun TabBarDnd.editorScreenRect(): Rectangle? {
    val origin = origin() ?: return null
    if (tabs.width <= 0 || tabs.height <= 0) return null
    return Rectangle(origin.x, origin.y, tabs.width, tabs.height)
}

/**
 * The drop side of [screen] in this split: `TabsUtil.getDropSideFor`, the very function `SingleRowLayout` / `TableLayout.getDropSideFor`
 * call for the platform's tab drag (public API, a trapezoid per edge sized by the registry key `ide.tabbedPane.dragToSplitRatio`),
 * given the point in the tab component's own coordinates. Null if [screen] is outside the split.
 */
internal fun TabBarDnd.dropSideAt(screen: Point): EditorSide? = EditorAreaDrop.sideOf(TabsUtil.getDropSideFor(toTabs(screen), tabs.component))

/**
 * The rectangle the platform highlights for a drop on [side] ([EditorAreaDrop.highlight] of the split without its tab row,
 * `JBTabs.getDropArea`), in screen coordinates; null while the split is not showing. The tab row is taken off with the strip's own
 * extent (its header rectangle; the platform uses the first tab label's height, which is the same for a single row and does not
 * know a hidden first tab).
 */
internal fun TabBarDnd.dropAreaScreen(side: EditorSide): Rectangle? {
    val whole = editorScreenRect() ?: return null
    val header = tabs.lastLayoutPass?.headerRectangle
    val strip = tabs.stripSide()
    val thickness = if (header == null) 0 else if (horizontal) header.height else header.width
    return EditorAreaDrop.highlight(EditorAreaDrop.dropArea(whole, strip, thickness), side)
}

/**
 * The editor split under [screen] (any split of the project, in any window) and the side of it, or null if the pointer is over none.
 * When windows overlap, the one that is the active window wins, otherwise the first.
 */
internal fun TabBarDnd.editorAreaAt(screen: Point): EditorAreaHit? {
    val candidates = peers().filter { it.editorScreenRect()?.contains(screen) == true }
    val chosen = candidates.firstOrNull { SwingUtilities.getWindowAncestor(it.tabs.component)?.isActive == true } ?: candidates.firstOrNull() ?: return null
    val side = chosen.dropSideAt(screen) ?: return null
    return EditorAreaHit(chosen, side)
}

/**
 * [screen] is inside the area of some editor window's splits (`EditorsSplitters`, which is what the platform's accept area is) without
 * being over a split: the divider between two splits. Dropping there makes no window (the platform would still add to the current
 * split; here the group goes back).
 */
internal fun TabBarDnd.overEditorRegion(screen: Point): Boolean = peers().any { peer ->
    val splitters = peer.adapter.window.owner
    splitters.isShowing && Rectangle(splitters.locationOnScreen, splitters.size).contains(screen)
}
