package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import dev.willcodes.bettereditortabs.tabgroups.model.strip.MoveUnit
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage

/**
 * What the user sees of a group drag besides the strip itself (INT-6): the floating picture of the group and, in another split, the
 * merge outline or insertion bar. Rendering only: it draws through the [DragOverlay] of the frame the pointer is over and decides
 * nothing; the gesture ([HeaderDragHelper]) tells it where the pointer and the target are.
 *
 * The picture is drawn by the overlay of the frame the pointer is over (a target in another frame takes it over), otherwise by the
 * frame it started in ([home]). In [home]'s own strip the live placeholder is the feedback, so no marker is drawn there. EDT only.
 */
internal class GroupDragFeedback(private val home: TabBarDnd) {
    private var ghostImage: BufferedImage? = null
    private var ghostSize = Dimension()
    private var ghostOwner: TabBarDnd? = null
    private var markerOwner: TabBarDnd? = null

    /** Size of the floating picture. */
    val size: Dimension get() = ghostSize

    /** Starts showing [image] in the home frame. */
    fun begin(image: BufferedImage?, size: Dimension) {
        ghostImage = image
        ghostSize = size
        ghostOwner = home
        home.overlay.setGhost(image, size)
    }

    /** Moves the picture to [screenTopLeft]; [over] is the split the pointer is over, if it is a target. */
    fun moveGhost(screenTopLeft: Point, over: TabBarDnd?) {
        // The picture only changes hands when the pointer moves to a split in another frame. Splits of one frame share the layered
        // pane the overlays live in, so any of their overlays can draw it; handing it over on every crossing between two strips
        // would add and remove a window-sized component each time, which repaints the whole frame while both strips re-lay out.
        val current = ghostOwner ?: home
        val wanted = if (over != null && over.tabs.rootPane !== current.tabs.rootPane) over else current
        if (wanted !== ghostOwner) {
            ghostOwner?.overlay?.setGhost(null, Dimension())
            ghostOwner = wanted
            wanted.overlay.setGhost(ghostImage, ghostSize)
        }
        wanted.overlay.moveGhost(screenTopLeft)
    }

    /**
     * The merge outline in another split's strip ([target]) when that split already has an instance of the group ([merge]); otherwise
     * (or for [home] itself, or null) nothing: where the group will land is shown as a gap in that strip, by the strip itself.
     * [units] are the target's drop units.
     */
    fun showMarker(target: TabBarDnd?, units: List<MoveUnit<*>>, merge: Boolean, groupId: String?) {
        if (markerOwner != null && markerOwner !== target) markerOwner?.overlay?.setMarker(null)
        markerOwner = target
        if (target == null || target === home) return
        val unit = if (merge) units.firstOrNull { it.groupId == groupId } else null
        if (unit == null) {
            target.overlay.setMarker(null)
            return
        }
        val origin = target.tabs.locationOnScreen
        val r = unit.bounds
        target.overlay.setMarker(Rectangle(origin.x + r.x, origin.y + r.y, r.width, r.height), true)
    }

    /** Removes the picture and the marker everywhere. */
    fun clear() {
        home.overlay.clear()
        markerOwner?.overlay?.clear()
        ghostOwner?.overlay?.clear()
        markerOwner = null
        ghostOwner = null
        ghostImage = null
    }
}
