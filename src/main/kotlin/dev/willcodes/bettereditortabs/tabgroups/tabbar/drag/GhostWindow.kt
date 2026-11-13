package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.ui.popup.PopupCornerType
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.WindowRoundedCornersManager
import com.intellij.util.IconUtil
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.Color
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
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
 * A group chip ([show] with `ownFrame`) brings its own rounded shape and border (see [GroupChipImage]): its window is transparent
 * (or, without per-pixel translucency, filled with the dialog colour) and has no platform corners.
 *
 * Not used on Wayland, where windows cannot be placed at a screen position; the platform uses a glass-pane view there and so does
 * [DragOverlay]. Wraps: `WindowRoundedCornersManager` (platform UI API), `WindowManager` alpha mode, `IconUtil.createImageIcon`. EDT only.
 */
internal object GhostWindow {
    private var dialog: JDialog? = null
    private var label: JLabel? = null
    private var owner: Any? = null
    private var image: Image? = null
    private var ownFrameShown = false

    /**
     * Shows [picture] for [by] (an overlay), its top-left corner at [screenTopLeft]; replaces whatever another owner showed.
     *
     * [ownFrame] (a group chip): the picture brings its own rounded shape and border, so the window must not add a frame around it:
     * no platform window corners, and a transparent window background wherever the platform can make per-pixel translucent windows
     * ([translucencySupported]); where it cannot, the label is opaque in the dialog colour, so the chip's corners show that colour.
     * The 90 % alpha stays. Tab pictures ([ownFrame] false) are shown exactly as the platform's.
     */
    fun show(by: Any, frame: Window?, picture: Image, screenTopLeft: Point, ownFrame: Boolean) {
        if (!enabled) return
        owner = by
        val d = dialog?.takeIf { it.owner === frame && it.isDisplayable && ownFrameShown == ownFrame } ?: create(frame, ownFrame)
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

    /** Can a window have a transparent background (per-pixel translucency)? False on failure, and in a headless environment. */
    fun translucencySupported(): Boolean = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
    }
    catch (e: Throwable) {
        false
    }

    private fun create(frame: Window?, ownFrame: Boolean): JDialog {
        dialog?.dispose()
        val l = JLabel()
        val d = JDialog(frame)
        d.isUndecorated = true
        d.focusableWindowState = false
        d.isAutoRequestFocus = false
        d.contentPane = l
        if (ownFrame) {
            if (translucencySupported()) {
                // must be set before the window is displayable
                d.background = Color(0, 0, 0, 0)
                l.isOpaque = false
            }
            else {
                l.isOpaque = true
                l.background = DragImages.windowBackground()
            }
        }
        else if (PlatformInternals.isRoundedTabDuringDrag()) {
            WindowRoundedCornersManager.setRoundedCorners(d, PopupCornerType.RoundedWindow)
        }
        ownFrameShown = ownFrame
        dialog = d
        label = l
        image = null
        return d
    }

    /** Off in the tests (no real windows on the test machine's screen, and the test window manager has no alpha mode). */
    @Volatile
    internal var enabled = true

    /** The platform's drag image window: 90 % opaque. */
    private const val ALPHA_RATIO = 0.1f
}
