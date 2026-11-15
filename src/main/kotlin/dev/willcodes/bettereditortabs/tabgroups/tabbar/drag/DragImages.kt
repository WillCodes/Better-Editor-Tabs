package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.util.ui.ImageUtil
import com.intellij.util.ui.UIUtil
import dev.willcodes.bettereditortabs.tabgroups.tabbar.GroupDecoration
import dev.willcodes.bettereditortabs.tabgroups.tabbar.StripStyle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.GroupTintColor
import java.awt.Color
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.UIManager

/**
 * The floating picture of a tab or group drag: the picture the platform shows when a tab is dragged, produced by the platform itself.
 *
 * How the platform makes it (`DockableEditorTabbedContainer.processDropOver`, `DockableEditorTabbedContainer.kt:271-282`): a *new*
 * `TabInfo(JLabel(""))` with the dragged tab's text and icon is handed to `JBTabs.startDropOver`, which creates a fresh tab label for
 * it and paints that offscreen (`JBTabsImpl.kt:3367-3383`). That label is not the selected tab and not hovered, so it is the plain,
 * muted tab: icon and name, no selected outline or background, no close button. `DockManagerImpl` then shows the image in an
 * undecorated dialog at 90 % opacity, centred on the pointer (`DockManagerImpl.kt:235-241`, `:363-364`).
 *
 * Calling the same function here makes the picture identical by construction. `startDropOver` also adds the drop target to the strip, so
 * it is removed again at once with `resetDropOver` (nothing lays out in between). The earlier version painted the *dragged tab's own
 * label*, which is the selected tab: that is what made the picture look selected (outline, fill, close button).
 *
 * The live tint of a drag is the `tabColor` of that new `TabInfo`, which the tab painter turns into a tint of the muted tab.
 *
 * The platform's image is transparent except for the icon and the name. What the user sees behind them is the dialog that shows it,
 * so the picture is returned as it is (no background) and shown in the same kind of dialog ([GhostWindow]). [windowBackground] (the
 * look-and-feel's `control` colour, which `JDialog.dialogInit` gives a dialog) is only the fallback for pictures that are drawn into
 * the frame (Wayland) or snapshotted by hand.
 *
 * Wraps: `JBTabs.startDropOver` / `resetDropOver`, `TabInfo`. EDT only.
 */
internal object DragImages {
    /** The picture of the dragged tab [dragged], tinted when it is previewed into a group. Null if the platform could not make it. */
    fun tab(tabs: JBEditorTabs, dragged: TabInfo): BufferedImage? =
        platformImage(tabs, dragged.text, dragged.icon, dragged.tabColor as? GroupTintColor)

    /**
     * The picture of a tab that is dragged over a strip it does not belong to: made by [tabs], the strip it is over, from
     * the tab's [text] and [icon] alone, tinted with [tint] (that strip's tint colour of the group it would join, null: untinted).
     */
    fun tab(tabs: JBEditorTabs, text: String, icon: Icon?, tint: Color?): BufferedImage? = platformImage(tabs, text, icon, tint)

    /**
     * The picture of a dragged group: a bordered chip with the group's pill and the number of tabs moved ([tabCount], from the
     * model's `GroupDecoration.members`: a collapsed group's tabs are hidden in the strip). Shown without the dialog's own frame
     * (see [GroupChipImage], [GhostWindow]).
     */
    fun groupChip(tabs: JBEditorTabs, decoration: GroupDecoration, tabCount: Int, style: StripStyle): BufferedImage? =
        GroupChipImage.create(tabs, decoration, tabCount, style)

    /** The height in layout pixels of the platform's tab picture, so the group chip is as tall; null if the platform made none. */
    fun tabPictureHeight(tabs: JBEditorTabs): Int? = rawImage(tabs, "M", null, null)?.let { sizeOf(it).height }

    private fun platformImage(tabs: JBEditorTabs, text: String, icon: Icon?, tint: Color?): BufferedImage? {
        val image = rawImage(tabs, text, icon, tint) ?: return null
        if (tint == null) return image
        val plain = rawImage(tabs, text, icon, null) ?: return image
        fillRim(image, plain)
        return image
    }

    /**
     * The tinted picture: the tab painter fills the tint in the tab's shape, which in Islands is a rectangle with rounded
     * corners inset from the picture's edges. The picture is shown in a window of its own ([GhostWindow]), so the window's background
     * would show as a ring around the tint (the platform never tints its picture). The tint fill is found by comparison with the
     * untinted picture ([plain]: the most frequent colour among the pixels that differ) and painted into every pixel that has less of
     * it, so the whole window is tinted evenly; pixels inside the tab (the fill, the icon and the name over it) stay as they are.
     */
    private fun fillRim(image: BufferedImage, plain: BufferedImage) {
        val w = minOf(image.width, plain.width)
        val h = minOf(image.height, plain.height)
        val counts = HashMap<Int, Int>()
        for (y in 0 until h) for (x in 0 until w) {
            val p = image.getRGB(x, y)
            if (p != plain.getRGB(x, y) && (p ushr 24) != 0) counts.merge(p, 1, Int::plus)
        }
        val fill = counts.maxByOrNull { it.value }?.key ?: return
        val fillAlpha = fill ushr 24
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if ((image.getRGB(x, y) ushr 24) < fillAlpha) image.setRGB(x, y, fill)
        }
    }

    private fun rawImage(tabs: JBEditorTabs, text: String, icon: Icon?, tint: Color?): BufferedImage? {
        val drop = TabInfo(JLabel("")).setText(text).setIcon(icon)
        if (tint != null) drop.setTabColor(tint)
        val platform = try {
            tabs.startDropOver(drop, RelativePoint(tabs.component, Point(0, 0)))
        }
        catch (e: Exception) {
            null
        }
        finally {
            try {
                tabs.resetDropOver(drop)
            }
            catch (e: Exception) {
                // nothing to remove
            }
        }
        platform ?: return null
        val w = platform.getWidth(null)
        val h = platform.getHeight(null)
        if (w <= 0 || h <= 0) return null
        val image = ImageUtil.createImage(tabs.graphicsConfiguration, w, h, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.drawImage(platform, 0, 0, w, h, null)
        }
        finally {
            g.dispose()
        }
        return image
    }

    /** The background of the platform's drag-image dialog (`JDialog.dialogInit`: the look-and-feel's `control` colour). */
    fun windowBackground(): Color = UIManager.getColor("control") ?: UIUtil.getPanelBackground()

    /** The size of [image] in layout pixels. */
    fun sizeOf(image: BufferedImage): java.awt.Dimension = java.awt.Dimension(image.getWidth(null), image.getHeight(null))
}
