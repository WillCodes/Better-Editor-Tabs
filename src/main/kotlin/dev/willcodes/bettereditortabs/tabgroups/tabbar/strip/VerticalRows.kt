package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.ui.tabs.impl.singleRow.SingleRowPassInfo
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PillRows
import dev.willcodes.bettereditortabs.tabgroups.model.strip.animatedLength
import java.awt.Rectangle

/**
 * Gives pills their own row in a side strip (Left / Right placement) by moving the tab labels down after the platform's layout.
 *
 * Why not through the label like Top / Bottom ([TabLabelSpace]): the platform takes a label's height from
 * `EditorTabLabel.getPreferredHeight()` (`EditorTabbedContainer.kt:784-789`), which depends on the tab bar only (label insets and
 * `layoutInsets`); `EditorTabLabel` is private and `JBTabsImpl.createTabLabel` cannot be replaced from outside, so no per-label height
 * exists. After every platform layout pass ([TabBarAdapter]'s layout hook) every label that has blocks in front of it (pill rows, the
 * space opened for a group dragged in from another split) is moved down by their height; the pill components are then placed in the
 * freed rows ([StripGeometry]). A pass is shifted once ([lastPass] identity), so the hook may run twice.
 *
 * The end of the strip: shifted labels never cover the overflow buttons ("more", entry point). Like the platform's own labels they
 * are narrowed in the buttons' row and hidden below the end of the strip ([PillRows.fit]); the hidden ones are added to the pass's
 * dropped tabs, so "Show Hidden Tabs" lists them.
 *
 * Collapse animation (COLL-7): the label of a group that collapses or expands is drawn at the animated part of its height
 * ([CollapseAnimator.verticalExtentOf]) and the labels behind it move up by the rest, frame by frame (every frame is a new layout pass).
 *
 * The "more" button ([SideMoreButton]): a strip that fits without the pill rows but not with them has no platform "more" button (the
 * platform makes it from the raw lengths). When labels are pushed out of such a strip the button is made where the platform would put
 * it and the labels are fitted around it, so the pushed-out tabs are reachable through the overflow list. During a collapse animation
 * a shown button stays until the animation ends.
 *
 * Limits: scrolling and scroll-to-selected still work from the raw label heights and do not count the pill rows: the platform cannot
 * scroll a strip it considers fitting, so there the selected tab is brought into view by [PillRows.extraScroll] (the start of the strip
 * scrolls away); an overflowing strip is corrected by moving everything back by up to the height of all blocks so the selected tab (or
 * the end of the strip) stays visible, with a small jump when the end is reached. The "more" button is not made in multi-row layouts.
 *
 * Wraps: `JBTabsImpl.lastLayoutPass` (`requiredLength`, `scrollExtent`, `moreRect`, `entryPointRect`, `SingleRowPassInfo.toDrop`),
 * `isMouseInsideTabsArea`, `isRecentlyActive`, label bounds. EDT only.
 */
internal class VerticalRows(
    private val tabs: JBEditorTabs,
    private val layout: PillLayout,
    private val geometry: StripGeometry,
    private val extentOf: (VirtualFile) -> Double? = { null },
) {
    private var lastPass: Any? = null
    private val moreButton = SideMoreButton(tabs)

    /** The laid-out height of every label the last pass drew shorter (COLL-7); pill rows keep the full row height. */
    private val naturalHeights = java.util.IdentityHashMap<TabLabel, Int>()

    /** The height of [label]'s row: its laid-out height, also while the animation draws it shorter. */
    fun rowHeight(label: TabLabel): Int = naturalHeights[label] ?: label.height

    /** Moves the labels of the pass that was just laid out. Returns true if something moved. */
    fun apply(): Boolean {
        if (tabs.isHorizontalTabs || tabs.isHideTabs) {
            naturalHeights.clear()
            lastPass = null
            moreButton.reset()
            return false
        }
        val pass = tabs.lastLayoutPass ?: return false
        if (pass === lastPass) return false
        lastPass = pass
        val labels = tabs.getVisibleInfos().mapNotNull { tabs.getTabLabel(it) }
        val lead = ArrayList<Int>()
        val trail = ArrayList<Int>()
        for (label in labels) {
            val (l, t) = layout.itemsOf(label)
            lead += l.sumOf { if (it.header != null || it.count != null) label.height else it.length }
            trail += t.sumOf { if (it.header != null || it.count != null) label.height else it.length }
        }
        // COLL-7: a label of a group that collapses / expands is drawn at its animated height; the labels behind it move up by the rest.
        val drawn = IntArray(labels.size) { i ->
            val h = labels[i].height
            (labels[i].info.`object` as? VirtualFile)?.let(extentOf)?.let { animatedLength(h, it) } ?: h
        }
        val shrink = IntArray(labels.size) { labels[it].height - drawn[it] }
        naturalHeights.clear()
        for ((i, label) in labels.withIndex()) if (shrink[i] != 0) naturalHeights[label] = label.height
        val total = lead.sum() + trail.sum()
        if (total == 0 && shrink.all { it == 0 }) return false
        val shifts = PillRows.shifts(lead, trail, shrink)
        val limit = geometry.limitFunction(false)(labels.firstOrNull { !it.bounds.isEmpty }?.bounds ?: return false)
        var selectedEnd: Int? = null
        var lastEnd = 0
        val selected = tabs.selectedInfo?.let { tabs.getTabLabel(it) }
        for ((i, label) in labels.withIndex()) {
            val b = label.bounds
            if (b.isEmpty) continue
            val end = b.y + shifts[i] + drawn[i]
            if (label === selected) selectedEnd = end
            lastEnd = maxOf(lastEnd, end + trail[i])
        }
        val overflowing = pass.requiredLength > pass.scrollExtent
        // scrolled to the end: the strip's very last label is laid out and ends inside the strip (an overflowing strip clips or drops it otherwise)
        val lastLabel = labels.lastOrNull()?.bounds?.takeIf { !it.isEmpty }
        val atEnd = lastLabel != null && lastLabel.y + lastLabel.height <= limit + 2
        val back = PillRows.extraScroll(
            selectedEnd, lastEnd, limit, total,
            followsSelection = !tabs.isMouseInsideTabsArea && !tabs.isRecentlyActive,
            atEnd = atEnd,
            overflowing = overflowing,
        )
        // Like the platform's own labels, a shifted label never covers the overflow buttons at the end of the strip: it is narrowed
        // when it shares their row and hidden when it does not fit above the end of the strip. The platform may have narrowed the
        // label that was in the buttons' row before the shift, so every label gets the column's full width back first.
        val column = labels.filter { !it.bounds.isEmpty }.maxOfOrNull { it.width } ?: 0
        val animating = shrink.any { it != 0 }
        fun fitAll(buttons: List<Rectangle>): Array<Rectangle?> = Array(labels.size) { i ->
            val b = labels[i].bounds
            if (b.isEmpty || drawn[i] == 0) null
            else PillRows.fit(Rectangle(b.x, b.y + shifts[i] - back, column, drawn[i]), buttons, tabs.height)
        }
        var buttons = listOf(pass.moreRect, pass.entryPointRect).filter { !it.isEmpty }
        var fits = fitAll(buttons)
        // A strip that fits raw but not with the pill rows has no "more" button yet (the platform makes it from the raw lengths): make it
        // when labels are pushed out. During a collapse animation a button that is shown stays until the animation ends, so it does not
        // come and go with every frame (the decision is made from the layout without the button, so it is not circular).
        val needed = labels.indices.any { fits[it] == null && !labels[it].bounds.isEmpty && drawn[it] != 0 }
        val moreRect = if (needed || (animating && moreButton.shown)) moreButton.rectFor(pass) else null
        if (moreRect != null) {
            moreButton.show(pass, moreRect)
            buttons = buttons + moreRect
            fits = fitAll(buttons)
        }
        else moreButton.reset()
        val pushedOut = ArrayList<TabInfo>()
        for ((i, label) in labels.withIndex()) {
            val b = label.bounds
            if (b.isEmpty) continue
            if (drawn[i] == 0) {
                // fully shrunk by the animation: not shown, but not "pushed out" either (it is not listed under Show Hidden Tabs)
                label.setBounds(b.x, b.y + shifts[i] - back, column, 0)
                continue
            }
            val fitted = fits[i]
            if (fitted == null) {
                label.setBounds(0, 0, 0, 0)
                pushedOut += label.info
            }
            else if (label.bounds != fitted) {
                label.bounds = fitted
            }
        }
        // Tabs pushed out by the pill rows are listed under "Show Hidden Tabs" like the ones the platform dropped itself
        // (`SingleRowLayout.isTabHidden` reads this list of the pass).
        (pass as? SingleRowPassInfo)?.toDrop?.let { dropped -> for (info in pushedOut) if (info !in dropped) dropped += info }
        scrolledBack = back
        return true
    }

    /** How far the last pass moved everything back up ([PillRows.extraScroll]); pills and spacers are placed from the labels, so they follow. */
    var scrolledBack = 0
        private set
}
