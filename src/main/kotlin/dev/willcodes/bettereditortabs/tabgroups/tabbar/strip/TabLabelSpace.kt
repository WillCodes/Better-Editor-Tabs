package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.ui.tabs.impl.TabLabel
import java.awt.Component
import java.awt.Graphics
import java.awt.Insets
import java.beans.PropertyChangeListener
import java.util.WeakHashMap
import javax.swing.border.AbstractBorder
import javax.swing.border.Border

/**
 * A label border that adds leading / trailing space to the border the platform put on the label.
 *
 * Why this works: `TabLabel` is a `JPanel` with a `BorderLayout`, and `TabLabel.getPreferredSize` is the layout's preferred
 * size, which includes the border insets (`EditorTabLabel.getPreferredSize` keeps that width and only replaces the height).
 * So every platform layout sees a label that is wider by exactly the reserved space, and the label's content (icon, text,
 * close button) is laid out inside the insets.
 */
internal class ReservedSpaceBorder(
    val inner: Border?,
    val leading: Int,
    val trailing: Int,
    private val tabs: JBTabsImpl? = null,
) : AbstractBorder() {
    override fun getBorderInsets(c: Component?, insets: Insets): Insets {
        val i = inner?.getBorderInsets(c)
        insets.top = i?.top ?: 0
        insets.bottom = i?.bottom ?: 0
        insets.left = (i?.left ?: 0) + leading
        insets.right = (i?.right ?: 0) + trailing
        return insets
    }

    override fun paintBorder(c: Component?, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        inner?.paintBorder(c, g, x + leading, y, width - leading - trailing, height)
        // A label paints its background (tab fill, tint, selected / hover state) over its whole bounds in paintComponent, which
        // runs before the border. The reserved area belongs to the pill, so the strip is shown there instead (see PillSpacePainting).
        if (tabs != null && c is TabLabel && leading + trailing > 0) PillSpacePainting.clearReserved(tabs, c, g, leading, trailing)
    }

    override fun isBorderOpaque(): Boolean = false
}

/**
 * Reserves space inside tab labels for group pills (see [ReservedSpaceBorder]).
 *
 * `TabLabel.apply(UiDecoration)` (`TabLabel.kt:564`) replaces the label border with a plain `EmptyBorder` whenever the platform
 * applies its decoration (tab added, UI refresh, `JBTabsImpl.applyDecoration`). A `border` property listener on each reserved
 * label wraps the new border again at once, so the reservation survives and no layout pass sees a label without it.
 *
 * EDT only. Wraps: `TabLabel` (internal) and its `border` property.
 */
internal class TabLabelSpace(private val tabs: JBTabsImpl? = null) {
    private class Entry(var leading: Int, var trailing: Int)

    private val entries = WeakHashMap<TabLabel, Entry>()
    private val listeners = WeakHashMap<TabLabel, PropertyChangeListener>()
    private var wrapping = false

    /** The reserved (leading, trailing) space of [label]. */
    fun reserved(label: TabLabel): Pair<Int, Int> = entries[label]?.let { it.leading to it.trailing } ?: (0 to 0)

    /** Reserves the space on [label]. Returns true if the label border had to be changed (a new layout pass is needed). */
    fun set(label: TabLabel, leading: Int, trailing: Int): Boolean {
        val entry = entries.getOrPut(label) { Entry(0, 0) }
        entry.leading = leading
        entry.trailing = trailing
        if (leading + trailing > 0 && label !in listeners) {
            // The listener must not capture the label (the map is weak): the event source is the label.
            val listener = PropertyChangeListener { e ->
                val source = e.source as? TabLabel ?: return@PropertyChangeListener
                if (!wrapping && e.newValue !is ReservedSpaceBorder) rewrap(source)
            }
            listeners[label] = listener
            label.addPropertyChangeListener("border", listener)
        }
        return rewrap(label)
    }

    private fun rewrap(label: TabLabel): Boolean {
        val entry = entries[label] ?: return false
        val current = label.border
        val currentReserved = current as? ReservedSpaceBorder
        val wanted = entry.leading + entry.trailing > 0
        if (currentReserved != null && wanted && currentReserved.leading == entry.leading && currentReserved.trailing == entry.trailing) return false
        if (currentReserved == null && !wanted) return false
        val inner = currentReserved?.inner ?: current
        wrapping = true
        try {
            label.border = if (wanted) ReservedSpaceBorder(inner, entry.leading, entry.trailing, tabs) else inner
        }
        finally {
            wrapping = false
        }
        return true
    }

    /** Removes the reservation from every label that is not in [keep]. Returns true if a border changed. */
    fun releaseExcept(keep: Collection<TabLabel>): Boolean {
        var changed = false
        for (label in entries.keys.toList()) {
            if (keep.any { it === label }) continue
            val entry = entries[label] ?: continue
            if (entry.leading + entry.trailing == 0 && label.border !is ReservedSpaceBorder) continue
            entry.leading = 0
            entry.trailing = 0
            changed = rewrap(label) || changed
        }
        return changed
    }

    /** Removes every reservation and listener (adapter disposal). */
    fun dispose() {
        for (label in entries.keys.toList()) {
            entries[label]?.let { it.leading = 0; it.trailing = 0 }
            rewrap(label)
        }
        for ((label, listener) in listeners.entries.toList()) label.removePropertyChangeListener("border", listener)
        listeners.clear()
        entries.clear()
    }
}
