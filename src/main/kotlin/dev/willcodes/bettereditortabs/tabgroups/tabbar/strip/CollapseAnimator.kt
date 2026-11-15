package dev.willcodes.bettereditortabs.tabgroups.tabbar.strip

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.ui.tabs.impl.TabLabel
import dev.willcodes.bettereditortabs.tabgroups.model.strip.COLLAPSE_ANIMATION_MS
import dev.willcodes.bettereditortabs.tabgroups.model.strip.CollapseTween
import dev.willcodes.bettereditortabs.tabgroups.model.strip.animatedLength
import java.awt.Dimension
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.JComponent
import javax.swing.Timer

/**
 * The collapse / expand animation of one strip: widths in Top / Bottom strips, heights in Left / Right ones ([verticalExtentOf]). The tween itself is pure ([CollapseTween]); this class
 * owns what is platform: the running animations, the frame timer and the label sizes.
 *
 * How it animates: every platform tab layout sizes a label from `label.preferredSize` (`TabLabel.getPreferredSize` ends in
 * `JComponent.getPreferredSize`, and `EditorTabLabel` keeps that width), so setting the preferred *width* of each member label
 * makes the platform itself shrink the tabs, slide the following ones, reflow rows and count overflow. The space reserved inside a
 * label for pills ([TabLabelSpace]) is kept in full: the first member hosts its group's pill.
 *
 * Internal APIs wrapped: `TabLabel.setPreferredSize` (an animated label has a fixed width until the animation ends, then the size is
 * cleared again), `JBTabsImpl.getVisibleInfos` / `getTabLabel` (which labels exist) and `revalidate` (the next frame's layout).
 *
 * The animator does not hide or show tabs and does not know about selection: while a collapse runs, the tabs are still visible and the
 * owner ([TabBarAdapter]) treats the group as expanded ([isCollapsing]); when a collapse is over, [onCollapseFinished] is called so
 * that the owner hides the tabs. EDT only.
 */
internal class CollapseAnimator(
    private val tabs: JBEditorTabs,
    private val space: TabLabelSpace,
    private val onCollapseFinished: () -> Unit,
) {
    /**
     * [members] are the labels that animate; [target] the files the group shows when the animation is over (a partial group
     * shows its peek, so the files that shrink or grow are not always all of the group's tabs).
     */
    private class Anim(var tween: CollapseTween, var members: List<VirtualFile>, var target: Set<VirtualFile>)

    /** Injectable for tests; nanoseconds on any monotonic clock. */
    var clock: () -> Long = System::nanoTime

    private val animations = LinkedHashMap<String, Anim>()
    private val touched: MutableSet<TabLabel> = Collections.newSetFromMap(WeakHashMap())
    private var timer: Timer? = null
    private val fullNanos = COLLAPSE_ANIMATION_MS * 1_000_000L

    /** Side strips (Left / Right): the extent of every animating member's label as of the last [applySizes], for [VerticalRows]. */
    private var verticalExtents: Map<VirtualFile, Double> = emptyMap()

    /**
     * The extent (0..1) the label of [file] is drawn at in a side strip, or null if it does not animate. A side strip's label height is
     * fixed by the platform (`EditorTabLabel.getPreferredHeight`), so there the animation is applied after the layout by [VerticalRows],
     * which draws the label this much of its height and moves the labels behind it up.
     */
    fun verticalExtentOf(file: VirtualFile): Double? = verticalExtents[file]

    /** True while at least one animation runs. */
    val isActive: Boolean get() = animations.isNotEmpty()

    /** True while a collapse of [groupId] runs: the model says collapsed, the strip still shows its shrinking tabs. */
    fun isCollapsing(groupId: String): Boolean = animations[groupId]?.tween?.collapsing == true

    /** The direction of the animation of [groupId] (true = collapsing), or null if it does not animate. */
    fun direction(groupId: String): Boolean? = animations[groupId]?.tween?.collapsing

    /** The files that group [groupId] shows when its running animation ends, or null if it does not animate. */
    fun targetOf(groupId: String): Set<VirtualFile>? = animations[groupId]?.target

    /** The files of [groupId] that are on screen while its running collapse shrinks them: the shrinking ones and what stays. */
    fun shownDuringCollapse(groupId: String): Set<VirtualFile> =
        animations[groupId]?.let { it.target + it.members } ?: emptySet()

    /** Ends the animation of [groupId] at once (no frame, no [onCollapseFinished]); the caller re-applies its groups. */
    fun end(groupId: String) {
        animations.remove(groupId)
        if (animations.isEmpty()) stopTimer()
    }

    /** The id of the group whose collapse is running and that contains [file], or null. */
    fun collapsingGroupOf(file: VirtualFile): String? =
        animations.entries.firstOrNull { it.value.tween.collapsing && file in it.value.members }?.key

    /**
     * Starts the animation of [groupId] over [members] in the given direction. Running the other way: reverses from the current
     * extent. Running the same way: goes on (the members are updated).
     */
    fun begin(groupId: String, members: List<VirtualFile>, collapsing: Boolean) = begin(groupId, members, collapsing, emptySet())

    /**
     * [begin] where the group shows [target] when the animation is over (a partial expand grows only the peek, a collapse of a
     * partial group shrinks only the peek). [members] are the labels that animate.
     */
    fun begin(groupId: String, members: List<VirtualFile>, collapsing: Boolean, target: Set<VirtualFile>) {
        val now = clock()
        val running = animations[groupId]
        when {
            running == null -> animations[groupId] = Anim(CollapseTween.start(collapsing, now, fullNanos), members, target)
            running.tween.collapsing != collapsing -> {
                running.tween = running.tween.reversed(now, fullNanos)
                running.members = members
                running.target = target
            }
            else -> {
                running.members = members
                running.target = target
            }
        }
        ensureTimer()
    }

    /** Updates the members of a running animation of [groupId] (no-op if none runs). */
    fun updateMembers(groupId: String, members: List<VirtualFile>) {
        animations[groupId]?.members = members
    }

    /** Drops the animations of groups that are not in [ids] any more. */
    fun retain(ids: Set<String>) {
        animations.keys.retainAll(ids)
        if (animations.isEmpty()) stopTimer()
    }

    /**
     * Gives every member label of every running animation its width for the current extent (the pill space stays in full); in a side
     * strip it records the extents for [verticalExtentOf] instead. Labels that were sized before and are not any more get their natural
     * size back.
     */
    fun applySizes() {
        val now = clock()
        val sized = HashSet<TabLabel>()
        if (tabs.isHorizontalTabs) verticalExtents = emptyMap()
        if (!tabs.isHorizontalTabs) {
            // Left / Right: heights are applied after the layout (VerticalRows); a preferred width would change the strip's width.
            val extents = HashMap<VirtualFile, Double>()
            for (anim in animations.values) {
                val extent = anim.tween.extentAt(now)
                for (file in anim.members) extents.putIfAbsent(file, extent)
            }
            verticalExtents = extents
        }
        else if (animations.isNotEmpty()) {
            val visible = tabs.getVisibleInfos().associateBy { it.`object` as? VirtualFile }
            for (anim in animations.values) {
                val extent = anim.tween.extentAt(now)
                for (file in anim.members) {
                    val label = visible[file]?.let { tabs.getTabLabel(it) } ?: continue
                    if (!sized.add(label)) continue
                    clearSize(label)
                    val natural = label.preferredSize
                    val (lead, trail) = space.reserved(label)
                    val reserved = minOf(lead + trail, natural.width)
                    label.preferredSize = Dimension(reserved + animatedLength(natural.width - reserved, extent), natural.height)
                    touched += label
                }
            }
        }
        for (label in touched.toList()) {
            if (label in sized) continue
            clearSize(label)
            touched -= label
        }
    }

    /** One frame (the timer's, or a test's): sizes the labels, ends finished animations, asks for a layout. */
    fun tick() {
        val now = clock()
        var collapseFinished = false
        val it = animations.entries.iterator()
        while (it.hasNext()) {
            val anim = it.next().value
            if (!anim.tween.isDone(now)) continue
            if (anim.tween.collapsing) collapseFinished = true
            it.remove()
        }
        applySizes()
        if (animations.isEmpty()) stopTimer()
        tabs.revalidate()
        tabs.repaint()
        if (collapseFinished) onCollapseFinished()
    }

    /**
     * Snaps every animation to its end: sizes are cleared and the animations dropped. Returns true if a collapse was among them (the
     * tabs of that group still have to be hidden: the caller re-applies its groups). Does not call [onCollapseFinished].
     */
    fun finishAll(): Boolean {
        val collapsed = animations.values.any { it.tween.collapsing }
        animations.clear()
        verticalExtents = emptyMap()
        stopTimer()
        clearSizes()
        return collapsed
    }

    fun dispose() {
        animations.clear()
        verticalExtents = emptyMap()
        stopTimer()
        clearSizes()
    }

    /** `TabLabel` declares the size non-null in Kotlin; the underlying `JComponent.setPreferredSize(null)` is what un-sets it. */
    private fun clearSize(label: TabLabel) {
        (label as JComponent).preferredSize = null
    }

    private fun clearSizes() {
        for (label in touched.toList()) clearSize(label)
        touched.clear()
    }

    private fun ensureTimer() {
        if (timer != null) return
        timer = Timer(FRAME_MS) { tick() }.apply {
            isCoalesce = true
            start()
        }
    }

    private fun stopTimer() {
        timer?.stop()
        timer = null
    }

    private companion object {
        const val FRAME_MS = 16
    }
}
