package dev.willcodes.bettereditortabs.tabgroups.tabbar.ide

import com.intellij.icons.AllIcons
import javax.swing.border.Border
import java.awt.geom.RoundRectangle2D
import java.awt.Insets
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.ColorUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.ui.tabs.impl.MorePopupAware
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.willcodes.bettereditortabs.BetterEditorTabsBundle
import dev.willcodes.bettereditortabs.tabgroups.model.strip.OverflowEntries
import dev.willcodes.bettereditortabs.tabgroups.model.strip.OverflowItem
import dev.willcodes.bettereditortabs.tabgroups.model.strip.OverflowRow
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitBar
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import kotlin.math.min

/** A tab listed in the overflow list: its file and the strip's `TabInfo` (which exists, hidden or not, as long as the tab is open). */
internal class OverflowTab(val file: VirtualFile, val info: TabInfo)

/**
 * One list row: a tab, with the heading ([heading]) that stands above it when it starts a run of its group. Headings are not rows of
 * their own, so the keyboard and the mouse only ever land on tabs.
 */
internal class OverflowEntry(
    val tab: OverflowItem<OverflowTab>,
    val heading: OverflowRow.Heading?,
    /** The colour of the tab's group (null if ungrouped): the row and its heading are drawn on the group's tint. */
    val groupColor: Color?,
)

/**
 * The overflow ("more") list of an editor split's strip with groups (COLL-6), shown by [TabListOverride] instead of the platform's
 * `JBTabsImpl.showMorePopup`, whose list never contains the tabs of collapsed groups (they are hidden `TabInfo`s, so not in
 * `getVisibleInfos()`).
 *
 * What it lists and in which order is [OverflowEntries]'s business (pure, tested): in the strip's logical order, every tab the layout
 * cuts off (the platform's own rule, `TabLayout.isTabHidden` on the visible infos) plus all tabs of collapsed groups; each run of a
 * group's tabs starts with a heading, the group's name (or "Unnamed group") in italics. A group's heading and tabs are drawn on the
 * group's tint (the group colour mixed into the popup background at the tint opacity, SET-1), so the background tells the groups
 * apart; there are no separator lines (user request, Stage 8 fix round 1).
 *
 * It mirrors the platform's list (`JBTabsImpl.showListPopup`, read in the 262 sources): a `ListPopupStep` popup anchored below
 * `lastLayoutPass.moreRect`; a row is icon + coloured title + a hover action icon on the right (a pinned tab shows the pin and its
 * click runs the tab's label action, which unpins; a tab with label actions shows the close cross and a click closes it); after such
 * a click the list opens again with the same index selected; the strip's selected tab has the platform's indicator (a rounded bar in
 * the tab theme's underline colour and height at the row's leading edge) and the icons of the other tabs are faded
 * (`EditorTabs.unselectedAlpha`), as in the platform's list. Differences, deliberate: the renderer is our own (the platform's is a
 * private class), the action icon is always on the right. Choosing a visible tab selects it with focus (`JBTabsImpl.select`); choosing a tab of a collapsed group goes to
 * [SplitBar.collapsedTabChosen] (the group expands, COLL-4 / INT-5), the tab is not selected here.
 *
 * Uses (all public members; javap-checked in 262 and 263): `JBTabsImpl.lastLayoutPass`, `LayoutPassInfo.moreRect`,
 * `JBTabsImpl.tabsPosition`, `TabLayout.isTabHidden` (layout from [PlatformInternals.tabLayout]), `JBTabsImpl.select` / `removeTab`,
 * `TabInfo` getters, `JBTabsImpl.getTabLabel`, `UIUtil.findComponentOfType`, `SimpleColoredText.appendToComponent`, `ActionManager.tryToExecute`,
 * `JBPopupFactory.createListPopup(Project, ListPopupStep, Function)`. EDT only.
 */
internal object OverflowList {

    /**
     * The rows for [tabs]' strip and [bar]'s groups, in the shape the list shows them; [order] is the strip's logical order. What the
     * layout hides is read from the laid-out strip.
     */
    fun entries(tabs: JBTabsImpl, bar: SplitBar, order: List<VirtualFile>): List<OverflowEntry> {
        val groups = bar.groups
        val groupOf = HashMap<VirtualFile, GroupDecoration>()
        for (g in groups) for (f in g.members) groupOf.putIfAbsent(f, g)
        val infos = tabs.tabs.mapNotNull { info -> (info.`object` as? VirtualFile)?.let { it to info } }.toMap()
        val layout = PlatformInternals.tabLayout(tabs)
        val items = ArrayList<OverflowItem<OverflowTab>>()
        for (file in order) {
            val info = infos[file] ?: continue
            val group = groupOf[file]
            val hiddenByCollapse = info.isHidden && group != null
            val cut = !info.isHidden && layout != null && layout.isTabHidden(info)
            items += OverflowItem(OverflowTab(file, info), group?.id, hiddenByCollapse, cut)
        }
        val byId = groups.associateBy { it.id }
        val rows = OverflowEntries.build(items, groups.associate { it.id to it.name })
        val result = ArrayList<OverflowEntry>()
        var heading: OverflowRow.Heading? = null
        for (row in rows) {
            when (row) {
                is OverflowRow.Heading -> heading = row
                is OverflowRow.Tab -> {
                    result += OverflowEntry(row.item, heading, row.item.groupId?.let { byId[it]?.color })
                    heading = null
                }
            }
        }
        return result
    }

    /** The title of a group heading: its name, or the generic title for an unnamed group (HDR-4). */
    fun headingText(heading: OverflowRow.Heading): String =
        if (heading.isUnnamed) BetterEditorTabsBundle.message("overflow.group.unnamed") else heading.name

    /** Shows the list below the "more" button of [tabs]. Returns the popup, or null if nothing is listed or the button has no place. */
    fun show(project: Project, tabs: JBTabsImpl, bar: SplitBar, selectIndex: Int? = null): JBPopup? {
        val rect = tabs.lastLayoutPass?.moreRect ?: return null
        val list = entries(tabs, bar, bar.adapter.logicalOrder())
        if (list.isEmpty()) return null
        val view = View(project, tabs, bar, list)
        val step = view.step
        if (selectIndex != null) step.defaultOptionIndex = min(selectIndex, list.size - 1)
        val popup = JBPopupFactory.getInstance().createListPopup(project, step) { view.renderer }
        view.attach(popup)
        popup.content.putClientProperty(MorePopupAware::class.java, true)
        popup.show(RelativePoint(tabs, Point(rect.x, rect.y + rect.height)))
        return popup
    }

    /** One shown list: the step, the renderer and the mouse handling for the action icon. */
    /** The list's renderer for [entries], without a popup (tests: the rows are measured and painted headlessly). */
    internal fun rendererFor(project: Project, tabs: JBTabsImpl, bar: SplitBar, entries: List<OverflowEntry>): ListCellRenderer<OverflowEntry> =
        View(project, tabs, bar, entries).renderer

    private class View(
        private val project: Project,
        private val tabs: JBTabsImpl,
        private val bar: SplitBar,
        private val entries: List<OverflowEntry>,
    ) {
        /** Index of the row the pointer is on (for the hovered close icon), -1 for none. */
        private var hovered = -1

        /** A click on the action icon is also an ordinary click on the row: this keeps the step from choosing it. */
        private var actionClicked = false
        private var list: JList<*>? = null

        val step = object : BaseListPopupStep<OverflowEntry>(null, entries) {
            override fun onChosen(selectedValue: OverflowEntry, finalChoice: Boolean): PopupStep<*>? {
                if (actionClicked) {
                    actionClicked = false
                    return PopupStep.FINAL_CHOICE
                }
                choose(selectedValue)
                return PopupStep.FINAL_CHOICE
            }

            override fun getIconFor(value: OverflowEntry): Icon? = value.tab.tab.info.icon

            @Suppress("DialogTitleCapitalization")
            override fun getTextFor(value: OverflowEntry): String = value.tab.tab.info.text
        }

        val renderer = Renderer()

        /** Installs the action-icon mouse handling ahead of the list's own listeners, as the platform does. */
        fun attach(popup: ListPopup) {
            val jlist = UIUtil.findComponentOfType(popup.content, JList::class.java) ?: return
            list = jlist
            val handler = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) = hover(rowAt(jlist, e.point, true))
                override fun mouseExited(e: MouseEvent) = hover(-1)
                override fun mouseReleased(e: MouseEvent) {
                    val index = rowAt(jlist, e.point, true)
                    if (index >= 0) actionClicked(popup, index, e)
                }
            }
            val listeners = jlist.mouseListeners
            val motionListeners = jlist.mouseMotionListeners
            listeners.forEach(jlist::removeMouseListener)
            motionListeners.forEach(jlist::removeMouseMotionListener)
            jlist.addMouseListener(handler)
            jlist.addMouseMotionListener(handler)
            listeners.forEach(jlist::addMouseListener)
            motionListeners.forEach(jlist::addMouseMotionListener)
        }

        private fun hover(index: Int) {
            if (hovered == index) return
            hovered = index
            list?.repaint()
        }

        /** The row whose action icon is under [point], or -1 (no row there, or the row has no action). */
        @Suppress("SameParameterValue")
        private fun rowAt(jlist: JList<*>, point: Point, onIcon: Boolean): Int {
            val index = jlist.locationToIndex(point)
            if (index < 0 || index >= entries.size) return -1
            val cell = jlist.getCellBounds(index, index) ?: return -1
            if (!cell.contains(point)) return -1
            if (!onIcon) return index
            if (!hasAction(entries[index].tab.tab.info)) return -1
            return if (point.x >= cell.x + cell.width - actionZone()) index else -1
        }

        private fun hasAction(info: TabInfo): Boolean = info.isPinned || labelAction(info) != null

        /** The tab's own close action: the last of its label actions (the platform expects "the last one is CloseTab"). */
        private fun labelAction(info: TabInfo) = (info.tabLabelActions as? DefaultActionGroup)?.childActionsOrStubs?.lastOrNull()

        /** Close (or, for a pinned tab, unpin) from the list, then list again with the same index selected. */
        private fun actionClicked(popup: ListPopup, index: Int, e: MouseEvent) {
            val info = entries[index].tab.tab.info
            val action = labelAction(info)
            actionClicked = true
            e.consume()
            if (info.isPinned) {
                val component = tabs.getTabLabel(info)
                if (action != null && component != null) ActionManager.getInstance().tryToExecute(action, e, component, info.tabActionPlace, true)
            }
            else {
                tabs.removeTab(info)
            }
            popup.cancel()
            // later: the platform's listeners carry out the close, and the strip has to settle before it is read again
            ApplicationManager.getApplication().invokeLater(
                { if (!bar.split.window.isDisposed) show(project, tabs, bar, index) },
                Condition<Any?> { project.isDisposed },
            )
        }

        private fun choose(entry: OverflowEntry) {
            val item = entry.tab
            val groupId = item.groupId
            if (item.inCollapsedGroup && groupId != null) {
                // after the list is gone, so the expand animation and the focus change do not race its disposal
                ApplicationManager.getApplication().invokeLater(
                    { bar.collapsedTabChosen(groupId, item.tab.file) },
                    Condition<Any?> { project.isDisposed },
                )
            }
            else {
                tabs.select(item.tab.info, true)
            }
        }

        /**
         * The heading (when the row starts a run of its group), then the row: the active-tab indicator, icon, title and the action
         * icon. A grouped row and its heading are drawn on the group's tint; a selected row on the list's selection colour.
         */
        inner class Renderer : ListCellRenderer<OverflowEntry> {
            private val root = JPanel(BorderLayout())
            private val header = JLabel()
            private val row = JPanel()
            private val icon = JLabel()
            private val text = object : SimpleColoredComponent() {
                override fun getMaximumSize(): Dimension = preferredSize
            }
            private val action = JLabel()

            /** The row shows the strip's selected tab: it gets the platform's indicator. */
            private var active = false

            init {
                root.isOpaque = true
                header.isOpaque = true
                header.font = UIUtil.getListFont().deriveFont(Font.ITALIC)
                row.layout = BoxLayout(row, BoxLayout.X_AXIS)
                row.border = ActiveIndicator()
                text.setIpad(JBUI.emptyInsets())
                text.myBorder = null
                text.isOpaque = false
                row.add(icon)
                row.add(Box.createRigidArea(Dimension(JBUI.CurrentTheme.ActionsList.elementIconGap(), 0)))
                row.add(text)
                row.add(Box.createRigidArea(Dimension(JBUI.scale(30), 0)))
                row.add(Box.createHorizontalGlue())
                row.add(action)
                root.add(header, BorderLayout.NORTH)
                root.add(row, BorderLayout.CENTER)
                root.getAccessibleContext()
            }

            override fun getListCellRendererComponent(
                jlist: JList<out OverflowEntry>, value: OverflowEntry, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
            ): Component {
                val info = value.tab.tab.info
                val tint = value.groupColor?.let { ColorUtil.mix(jlist.background, it, tintOpacity()) }
                val heading = value.heading
                header.isVisible = heading != null
                header.text = heading?.let { headingText(it) }.orEmpty()
                header.background = tint ?: jlist.background
                header.foreground = jlist.foreground
                // the title starts where the tab titles start: the row's leading inset, an icon and the gap
                val titleStart = JBUI.scale(ROW_LEFT) + EmptyIcon.ICON_16.iconWidth + JBUI.CurrentTheme.ActionsList.elementIconGap()
                header.border = JBUI.Borders.empty(JBUI.scale(3), titleStart, JBUI.scale(3), JBUI.scale(3))
                root.background = jlist.background
                row.isOpaque = true
                row.background = when {
                    isSelected -> UIUtil.getListSelectionBackground(true)
                    tint != null -> tint
                    else -> info.tabColor ?: jlist.background
                }
                active = info == tabs.selectedInfo
                val tabIcon = info.icon
                icon.icon = if (tabIcon != null && !active) IconLoader.getTransparentIcon(tabIcon, JBUI.CurrentTheme.EditorTabs.unselectedAlpha()) else tabIcon
                text.clear()
                info.coloredText.appendToComponent(text)
                action.icon = actionIcon(info, index == hovered)
                // The components are reused for every row: without this their cached preferred sizes stay those of the first row
                // measured, so the list is as wide as that row and a longer row overflows it, its close button outside the popup
                // (user report, Stage 8 fix round 2). The platform's renderer does the same (`component.invalidate()`).
                for (c in listOf(text, icon, action, header, row, root)) c.invalidate()
                return root
            }

            private fun actionIcon(info: TabInfo, isHovered: Boolean): Icon = when {
                info.isPinned -> AllIcons.Actions.PinTab
                labelAction(info) != null -> if (isHovered) AllIcons.Actions.CloseHovered else AllIcons.Actions.Close
                else -> EmptyIcon.ICON_16
            }

            /** The row's insets, and the platform's active-tab indicator for the strip's selected tab (`JBTabsImpl.showListPopup`). */
            private inner class ActiveIndicator : Border {
                override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
                    if (!active) return
                    val theme = tabs.tabPainter.getTabTheme()
                    val inset = JBUI.scale(2)
                    val arc = JBUI.scale(4).toDouble()
                    val g2 = g.create() as Graphics2D
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2.color = theme.underlineColor
                        val bar = RoundRectangle2D.Double(x.toDouble(), (y + inset).toDouble(), theme.underlineHeight.toDouble(), (height - 2 * inset).toDouble(), arc, arc)
                        g2.fill(bar)
                    }
                    finally {
                        g2.dispose()
                    }
                }

                override fun getBorderInsets(c: Component): Insets = JBUI.insets(3, ROW_LEFT, 3, 3)

                override fun isBorderOpaque(): Boolean = false
            }
        }

        /** The tint opacity of the strip (SET-1), as the strip's own tabs use it. */
        private fun tintOpacity(): Double = bar.adapter.currentStyle().opacity.coerceIn(0.0, 1.0)
    }

    /** Leading inset of a row in unscaled pixels, as in the platform's list. */
    private const val ROW_LEFT = 9

    /** Width of the strip at a row's right edge in which a click is a click on the action icon. */
    private fun actionZone(): Int = JBUI.scale(28)
}
