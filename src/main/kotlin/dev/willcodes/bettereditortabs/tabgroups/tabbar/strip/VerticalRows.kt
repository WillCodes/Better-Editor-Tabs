package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.ui.tabs.impl.singleRow.SingleRowPassInfo
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PillRows
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
 * Limits: the platform decides about overflow ("more" button), scrolling and scroll-to-selected from the raw label heights. A strip that
 * fits raw but not with the pill rows shows no "more" button, so the tabs pushed out at its end can only be reached by selecting
 * them another way (the selected tab is always brought into view by [PillRows.extraScroll]); an overflowing strip is corrected by
 * moving everything back by up to the height of all blocks so the selected tab (or the end of the strip) stays visible, with a
 * small jump when the end is reached.
 *
 * Wraps: `JBTabsImpl.lastLayoutPass` (`requiredLength`, `scrollExtent`, `moreRect`, `entryPointRect`, `SingleRowPassInfo.toDrop`),
 * `isMouseInsideTabsArea`, `isRecentlyActive`, label bounds. EDT only.
 */
internal class VerticalRows(
    private val tabs: JBEditorTabs,
    private val layout: PillLayout,
    private val geometry: StripGeometry,
) {
    private var lastPass: Any? = null

    /** Moves the labels of the pass that was just laid out. Returns true if something moved. */
    fun apply(): Boolean {
        if (tabs.isHorizontalTabs || tabs.isHideTabs) {
            lastPass = null
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
            lead += l.sumOf { if (it.header != null) label.height else it.length }
            trail += t.sumOf { if (it.header != null) label.height else it.length }
        }
        val total = lead.sum() + trail.sum()
        if (total == 0) return false
        val shifts = PillRows.shifts(lead, trail)
        val limit = geometry.limitFunction(false)(labels.firstOrNull { !it.bounds.isEmpty }?.bounds ?: return false)
        var selectedEnd: Int? = null
        var lastEnd = 0
        val selected = tabs.selectedInfo?.let { tabs.getTabLabel(it) }
        for ((i, label) in labels.withIndex()) {
            val b = label.bounds
            if (b.isEmpty) continue
            val end = b.y + shifts[i] + b.height
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
        val buttons = listOf(pass.moreRect, pass.entryPointRect).filter { !it.isEmpty }
        val column = labels.filter { !it.bounds.isEmpty }.maxOfOrNull { it.width } ?: 0
        val pushedOut = ArrayList<TabInfo>()
        for ((i, label) in labels.withIndex()) {
            val b = label.bounds
            if (b.isEmpty) continue
            val fitted = PillRows.fit(Rectangle(b.x, b.y + shifts[i] - back, column, b.height), buttons, tabs.height)
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
