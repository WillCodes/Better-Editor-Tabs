package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ui.ExperimentalUI
import com.intellij.ui.tabs.JBTabsPosition
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.ui.tabs.impl.LayoutPassInfo
import com.intellij.ui.tabs.impl.singleRow.SingleRowPassInfo
import dev.willcodes.bettereditortabs.tabgroups.model.strip.PillRows
import java.awt.Rectangle

/**
 * Shows the platform's "more" button in a side strip that fits without its pill rows but not with them, where the platform
 * shows none: it only sets `LayoutPassInfo.moreRect` when `requiredLength > toFitLength` (`ScrollableSingleRowLayout.layoutMoreButton`)
 * and gives the more toolbar empty bounds then (`JBTabsImpl.centerMoreToolbarPosition`, 262 `JBTabsImpl.kt:2236`). [VerticalRows] calls
 * [rectFor] after a pass in which it pushed labels out, and [show] with the result: `pass.moreRect` is set to the rectangle the platform
 * would have computed (`PillRows.sideMoreRect`) and the toolbar gets the same bounds (New UI side placement uses the rect unchanged),
 * after which the labels are fitted around it like around a platform-made button.
 *
 * The button is the platform's own toolbar, so a click runs the `TabList` action: `TabListOverride` shows the grouped `OverflowList`
 * for a split with groups, otherwise the base action calls `JBTabsImpl.showMorePopup`; both list the tabs the layout reports as hidden
 * (`TabLayout.isTabHidden`, true for the labels pushed out: their bounds are empty and they are in `toDrop`). The action's `update`
 * is `canShowMorePopup`, which reads the `moreRect` set here.
 *
 * Nothing to undo when the button is not needed: the next platform pass leaves `moreRect` empty and sets empty bounds on the toolbar.
 * Churn: `JBTabsImpl.doLayout` ends with `updateToolbarIfVisibilityChanged(moreToolbar, boundsBefore)`; the bounds before the pass are
 * the ones set here (non-empty) and the platform's pass makes them empty, so every pass in which the button stays shown requests one
 * (coalesced, asynchronous) update of the toolbar's actions, whose result does not change. That update does not relayout the strip (a
 * stable button keeps the toolbar's preferred size), so there is no layout loop; passes only happen on real changes and animation frames.
 * Only a button that is shown is affected; nothing is requested while the strip does not overflow.
 *
 * Not available (no button is made): not New UI, a multi-row layout (its pass is not a [SingleRowPassInfo]), a strip whose more toolbar
 * is not visible (`JBTabsImpl.relayout`: only layouts that scroll show it), a platform pass that already made a button.
 *
 * Wraps: `LayoutPassInfo.moreRect` (internal API, public field), `JBTabsImpl.moreToolbar`, `getMoreToolbarPreferredSize`,
 * `getEntryPointPreferredSize`, `getActionsInsets`, `headerFitSize`, `SingleRowPassInfo.insets`. EDT only.
 */
internal class SideMoreButton(private val tabs: JBEditorTabs) {
    /** True while the last pass showed the button; [VerticalRows] keeps it for the rest of a collapse animation. */
    var shown = false
        private set

    /** Where the button goes in [pass], or null when it cannot be shown (see the class comment). */
    fun rectFor(pass: LayoutPassInfo): Rectangle? {
        if (!pass.moreRect.isEmpty || pass !is SingleRowPassInfo || !ExperimentalUI.isNewUI()) return null
        val left = when (tabs.tabsPosition) {
            JBTabsPosition.left -> true
            JBTabsPosition.right -> false
            else -> return null
        }
        val toolbar = tabs.moreToolbar?.component ?: return null
        if (!toolbar.isVisible) return null
        val header = tabs.headerFitSize ?: return null
        val insets = pass.insets ?: return null
        return PillRows.sideMoreRect(left, tabs.width, tabs.height, header.width, tabs.entryPointPreferredSize, tabs.moreToolbarPreferredSize, insets, tabs.getActionsInsets())
    }

    fun show(pass: LayoutPassInfo, rect: Rectangle) {
        pass.moreRect = rect
        tabs.moreToolbar?.component?.bounds = Rectangle(rect)
        shown = true
    }

    fun reset() {
        shown = false
    }
}
