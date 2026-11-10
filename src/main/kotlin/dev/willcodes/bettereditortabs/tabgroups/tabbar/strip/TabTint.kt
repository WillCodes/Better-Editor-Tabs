package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.Disposable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBEditorTabs
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import java.awt.Color
import java.beans.PropertyChangeListener
import java.util.WeakHashMap
import java.util.function.Supplier

/**
 * The colour a grouped tab is tinted with. A [JBColor] with a supplier, so it is evaluated at paint time: it follows theme
 * switches and the active painter (see [TabTint] for what each painter does with a tab colour). Also the marker that tells
 * our colours apart from the colours the platform or File Colors set.
 */
internal class GroupTintColor(
    val groupColor: Color,
    supplier: Supplier<Color>,
) : JBColor(supplier)

/**
 * Per-split background tint for the tabs of expanded groups.
 *
 * Wraps `TabInfo.setTabColor` / `TabInfo.tabColor` and `TabInfo.changeSupport` (`TabInfo.TAB_COLOR` property events), all
 * platform-internal. The platform paints `TabInfo.tabColor` as the tab background in every painter, per tab, so this is a
 * per-split mechanism: the same file can be tinted in one split and plain in another.
 *
 * Who else writes the colour: `EditorTabbedContainer.createTab` (async, at open), `EditorsSplitters.updateFileBackgroundColor`
 * and `FileEditorManagerImpl` (on every File Colors / scope change), all through `TabInfo.setTabColor` with the colour from
 * `EditorTabPresentationUtil.getEditorTabBackgroundColor` (File Colors, non-project-file colour). They would overwrite our
 * tint, so each tab has a `TAB_COLOR` listener: a colour that is not ours is stored as the tab's *base* colour and our tint is
 * written again at once.
 *
 * Policy: a tab of an expanded group shows the group tint *instead of* its base colour (the tint takes priority over File
 * Colors and the non-project-file colour); the base colour comes back when the tab leaves the group or the group is removed.
 *
 * EDT only.
 */
internal class TabTint(private val tabs: JBEditorTabs, parent: Disposable, private val alive: () -> Boolean = { true }) : Disposable {
    /** [base]: the colour the platform / File Colors last wrote (never one of ours). [tint]: the colour we wrote, if any. */
    private class State(var base: Color?, var tint: Color? = null)

    private val states = WeakHashMap<TabInfo, State>()
    private val listeners = WeakHashMap<TabInfo, PropertyChangeListener>()
    private var desired: Map<VirtualFile, Color> = emptyMap()

    /** Drag-preview overrides, by file: a tint, or [UNTINTED]. Win over [desired] (see [setPreview]). */
    private val previews = HashMap<VirtualFile, Color>()
    private val colors = java.util.IdentityHashMap<Color, Color>()
    private var disposed = false

    init {
        com.intellij.openapi.util.Disposer.register(parent, this)
    }

    /** One tint colour per group, shared by its tabs. */
    fun colorFor(groupColor: Color): Color = colors.getOrPut(groupColor) { GroupTintColor(groupColor, Supplier { effective(groupColor) }) }

    /** Sets the tint per file (files of expanded groups only) and applies it to every tab of the split, hidden ones included. */
    fun setDesired(tints: Map<VirtualFile, Color>) {
        desired = tints
        refresh()
    }

    /**
     * Shows [file]'s tab in this split as a member of a group with [groupColor], while a drag is in progress. Repaints at once;
     * survives the platform rewriting the tab colour.
     */
    fun setPreview(file: VirtualFile, groupColor: Color) {
        previews[file] = colorFor(groupColor)
        refresh(file)
    }

    /** Shows [file]'s tab as having left its group (no tint), whatever its membership says. */
    fun setPreviewUntinted(file: VirtualFile) {
        previews[file] = UNTINTED
        refresh(file)
    }

    fun clearPreviews() {
        if (previews.isEmpty()) return
        val files = previews.keys.toList()
        previews.clear()
        files.forEach { refresh(it) }
    }

    private fun refresh(file: VirtualFile) {
        if (disposed) return
        for (info in tabs.tabs) if (info.`object` == file) {
            ensureTracked(info)
            apply(info)
        }
        tabs.component.repaint()
    }

    /**
     * Re-applies the tint to all tabs; also picks up tabs opened since the last call. Idempotent and safe to call often: it
     * derives every tab's colour from the current wish (preview, else membership) and the tab's own state, never from what
     * was done before. A tab whose colour is one of ours but is no longer wanted is restored, even if its state was lost.
     */
    fun refresh() {
        if (disposed) return
        for (info in tabs.tabs) {
            ensureTracked(info)
            apply(info)
        }
    }

    private fun ensureTracked(info: TabInfo) {
        if (info in states) return
        val current = info.tabColor
        states[info] = State(base = if (current is GroupTintColor) null else current)
        // The listener must not capture `info` (it is the value of a weak map keyed by it): the event source is the TabInfo.
        val listener = PropertyChangeListener { e ->
            if (e.propertyName != TabInfo.TAB_COLOR || disposed) return@PropertyChangeListener
            val source = e.source as? TabInfo ?: return@PropertyChangeListener
            val state = states[source] ?: return@PropertyChangeListener
            val new = e.newValue as? Color
            if (new != null && new === state.tint) return@PropertyChangeListener // our own write
            state.base = if (new is GroupTintColor) null else new
            apply(source)
        }
        listeners[info] = listener
        info.changeSupport.addPropertyChangeListener(listener)
    }

    private fun wanted(info: TabInfo): Color? {
        val file = info.`object` as? VirtualFile ?: return null
        val preview = previews[file]
        return when {
            preview === UNTINTED -> null
            preview != null -> preview
            else -> desired[file]
        }
    }

    private fun apply(info: TabInfo) {
        val state = states[info] ?: return
        val want = wanted(info)
        val current = info.tabColor
        if (want != null) {
            if (current !== want) {
                state.tint = want
                info.setTabColor(want)
            }
        }
        else if (state.tint != null || current is GroupTintColor) {
            state.tint = null
            info.setTabColor(state.base)
        }
    }

    /** The tab left this tab bar for good. */
    fun forget(info: TabInfo) {
        listeners.remove(info)?.let { info.changeSupport.removePropertyChangeListener(it) }
        states.remove(info)
    }

    override fun dispose() {
        disposed = true
        for ((info, listener) in listeners.entries.toList()) info.changeSupport.removePropertyChangeListener(listener)
        if (alive()) {
            for ((info, state) in states.entries.toList()) {
                if (state.tint != null || info.tabColor is GroupTintColor) info.setTabColor(state.base)
            }
        }
        listeners.clear()
        states.clear()
        previews.clear()
    }

    // ---- the colour itself ---------------------------------------------------------------------------------------------

    private fun isIslands(): Boolean = PlatformInternals.isIslandsPainter(tabs.tabPainter)

    /**
     * What to hand to the platform so that the member tab ends up with [OPACITY] of the group colour over the strip background.
     *
     * Classic New UI (`JBEditorTabPainter` / `JBDefaultTabPainter.getCustomBackground`): the tab colour is painted as is
     * (unselected: blended with `inactiveColoredFileBackground`; selected: replaces the selected background), so the group
     * colour with alpha [OPACITY] is enough. The selected tab stays recognisable through the painter's own underline, which
     * does not depend on the tab colour.
     *
     * Islands (`IslandsTabPainter.paintTab`): an unselected tab paints `withAlpha(tabColor, 0.4)`, whatever the alpha of the
     * colour (`COLOR_TAB_ALPHA`), so the alpha cannot be used to get 25%. Instead the RGB is pre-blended towards the strip
     * background, `bg + k * (group - bg)` with `k = OPACITY / 0.4`, which gives exactly OPACITY once painted at 40%. The selected
     * tab keeps its rounded outline and paints the tab colour as an inner fill with its own alpha, so the colour gets alpha
     * `OPACITY / k` and the selected tab shows the same tint (and stays recognisable through the outline).
     * For OPACITY above 0.4 the unselected tab is capped at 0.4 by the platform; the selected tab still follows OPACITY.
     */
    private fun effective(group: Color): Color {
        val t = OPACITY
        if (!isIslands()) return ColorUtil.withAlpha(group, t)
        val k = minOf(1.0, t / ISLANDS_ALPHA)
        val bg = tabs.tabPainter.getBackgroundColor()
        fun mix(g: Int, b: Int) = (b + k * (g - b)).toInt().coerceIn(0, 255)
        return Color(mix(group.red, bg.red), mix(group.green, bg.green), mix(group.blue, bg.blue), (255 * (t / k)).toInt().coerceIn(0, 255))
    }

    companion object {
        /** Tint opacity. SET-1 will make this configurable. */
        const val OPACITY = GroupHeader.FILL_ALPHA

        /** Marker for a preview override that shows a tab as untinted. Compared by identity. */
        private val UNTINTED = Color(0, 0, 0, 0)

        /** `IslandsTabPainter.COLOR_TAB_ALPHA` */
        private const val ISLANDS_ALPHA = 0.4
    }
}
