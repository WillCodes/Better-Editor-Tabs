package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.ui.popup.PopupCornerType
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.WindowRoundedCornersManager
import com.intellij.util.IconUtil
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.Image
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JLabel

/**
 * The floating picture of the plugin's own tab and group drags, shown exactly like the platform shows the picture of its tab drag:
 * `DockManagerImpl.DragImageDialog` is an undecorated `JDialog` (owned by the frame) whose content pane is a label holding the
 * image, with rounded window corners in Islands (`InternalUICustomization.isRoundedTabDuringDrag` ->
 * `WindowRoundedCornersManager.setRoundedCorners(window, PopupCornerType.RoundedWindow)`) and an alpha ratio of 0.1 once shown
 * (`WindowManager.setAlphaModeEnabled` / `setAlphaModeRatio`, `DockManagerImpl.kt:343-364`). The image is the platform's own, with no
 * background of ours (see [DragImages]): what shows behind its icon and name is the dialog itself, so background colour, corners and
 * opacity are the platform's by construction in every theme. (The earlier in-frame overlay imitated it and differed in Islands.)
 *
 * One window for the whole IDE, shared by every split's [DragOverlay]: a drag that crosses into another split or frame hands the
 * picture over ([show] by the new owner) without closing and reopening a window. [hide] by an owner that no longer has the picture is
 * ignored. The dialog never takes focus (the platform's dialog may; ours stays out of the editor's way during an in-strip drag).
 * Mouse events of a drag keep going to the component where the button was pressed, so the window under the pointer does not
 * disturb the drag.
 *
 * Not used on Wayland, where windows cannot be placed at a screen position; the platform uses a glass-pane view there and so does
 * [DragOverlay]. Wraps: `WindowRoundedCornersManager` (platform UI API), `WindowManager` alpha mode, `IconUtil.createImageIcon`. EDT only.
 */
internal object GhostWindow {
    private var dialog: JDialog? = null
    private var label: JLabel? = null
    private var owner: Any? = null
    private var image: Image? = null

    /** Shows [picture] for [by] (an overlay), its top-left corner at [screenTopLeft]; replaces whatever another owner showed. */
    fun show(by: Any, frame: Window?, picture: Image, screenTopLeft: Point) {
        owner = by
        val d = dialog?.takeIf { it.owner === frame && it.isDisplayable } ?: create(frame)
        if (image !== picture) {
            image = picture
            label?.icon = IconUtil.createImageIcon(picture)
            d.pack()
        }
        moveTo(d, screenTopLeft)
        if (!d.isVisible) {
            d.isVisible = true
            val windows = WindowManager.getInstance()
            windows.setAlphaModeEnabled(d, true)
            windows.setAlphaModeRatio(d, ALPHA_RATIO)
        }
    }

    /** Moves the picture of [by] (no-op if [by] does not show it). */
    fun move(by: Any, screenTopLeft: Point) {
        if (owner !== by) return
        dialog?.let { moveTo(it, screenTopLeft) }
    }

    /** Removes the picture of [by] (no-op if another owner has taken it over). */
    fun hide(by: Any) {
        if (owner !== by) return
        owner = null
        image = null
        dialog?.dispose()
        dialog = null
        label = null
    }

    /** Like `DialogDragImageView.location`: always the packed size, so moving across screens with other scales never resizes it. */
    private fun moveTo(d: JDialog, topLeft: Point) {
        val wanted = Rectangle(topLeft, d.preferredSize)
        if (d.bounds != wanted) d.bounds = wanted
    }

    private fun create(frame: Window?): JDialog {
        dialog?.dispose()
        val l = JLabel()
        val d = JDialog(frame)
        d.isUndecorated = true
        d.focusableWindowState = false
        d.isAutoRequestFocus = false
        d.contentPane = l
        if (PlatformInternals.isRoundedTabDuringDrag()) WindowRoundedCornersManager.setRoundedCorners(d, PopupCornerType.RoundedWindow)
        dialog = d
        label = l
        image = null
        return d
    }

    /** The platform's drag image window: 90 % opaque. */
    private const val ALPHA_RATIO = 0.1f
}
