package dev.willcodes.bettereditortabs.tabgroups.tabbar.drag

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import dev.willcodes.bettereditortabs.tabgroups.model.strip.Landing
import dev.willcodes.bettereditortabs.tabgroups.model.strip.TabEditorDrop
import dev.willcodes.bettereditortabs.tabgroups.model.strip.TabEditorLanding
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.isPlaceholder
import java.awt.Point

private val LOG = logger<CrossSplitTabDrag>()

/**
 * The part of a tab drag that is ours, across every split of the project: from the moment the drag starts until the
 * pointer leaves every tab strip (then [TabDragController] hands it to the platform's drag-out) or the tab is dropped.
 *
 * Each move it asks [TabDragTargets] which strip the pointer is over: the [home] strip the tab comes from, another split's strip, or
 * none. The [session] always runs on the strip the pointer is over:
 * - home: the home [TabDragSession], which rests (strip back as it was, [TabDragSession.suspend]) while the pointer is elsewhere and
 *   is resumed when it is back, keeping its own-group rule;
 * - another split: a new [TabDragSession] for that strip with the foreign tab (a gap opens there, the tab is moved on release),
 *   dropped when the pointer moves on. The new strip is entered before the old one is given back, and a strip that cannot take the
 *   tab changes nothing, so the drag never ends up on no strip by accident.
 * The picture ([feedback]) lives across sessions and follows the pointer into other frames.
 *
 * While the drag is on another split's strip the tab is **out of its home strip**, as the platform does when a tab is dragged out
 * (`EditorTabbedContainerDragOutDelegate.dragOutStarted`: the editor area selects the tab it would select, then the tab is hidden;
 * `dragOutCancelled` shows it again): the home strip is put back as it was and then [StripDragSurface.beginTransit] takes the tab out
 * and hands the selection over. Back on the home strip the tab is shown and selected again ([StripDragSurface.endTransit]) before the home
 * session resumes; Esc puts everything back, selection included; a copy drop shows it again, a move closes it.
 *
 * A strip that is longer than its window scrolls while the pointer is in its edge zone ([DragAutoScroll]); each tick re-runs [move], so
 * the slot follows the pointer through the scrolled strip, on the home strip and on another split's strip alike.
 *
 * **A bare editor**: while the pointer is off every strip and over the editor area of a split that shows no tab
 * but the dragged one ([TabEditorDrop]: every other tab is hidden by a collapsed group, or it is a temporary split), the drag stays ours
 * instead of being handed to the platform, whose drop session sees no visible tab there and always answers "middle" (no edge, no split).
 * The tab is out of its strip as over another split's strip, the platform's drop-area highlight shows the half that becomes a new split
 * (or the whole area for the middle), and the release makes a split on that side with the tab ([createSplitFor], [moveTabToCreatedSplit]),
 * moves it into another bare split (middle) or changes nothing (the middle of its own split).
 *
 * Drop in the home strip: [TabDragSession.commit]. Drop in another split: [moveTabToSplit] with both strips frozen as they were at the
 * release ([freezeStrips]). Esc: [cancel] puts every strip back. EDT only.
 */
internal class CrossSplitTabDrag(private val home: TabBarDnd, private val dragged: DraggedTab) {
    private val feedback = GroupDragFeedback(home)
    private val homeSession = TabDragSession(home, dragged, feedback)
    private var homeBegun = false

    /** The session that runs now and the strip it runs on. */
    private var session: TabDragSession? = null
    private var runsOn: TabBarDnd? = null

    /** The dragged tab is out of the home strip (the drag is on another split's strip). */
    private var inTransit = false

    /** The bare editor area the pointer is over ([EditorTarget]); the drag is on no strip then (no [session]). */
    private var editor: EditorTarget? = null

    /** What a release over a bare editor area does: [landing] on the split [hit]. */
    private class EditorTarget(val hit: EditorAreaHit, val landing: TabEditorLanding)

    /** Scrolls the strip under the pointer near its ends; every tick moves like the mouse does. */
    internal val scroll = DragAutoScroll { screen -> move(screen) }

    /** Which strip the pointer is over; also what the strips looked like (taking the drag back from the platform, [TabDragTargets.stripAt]). */
    val targets = TabDragTargets(home) { TabDragSession.accepts(it, dragged.file) }

    /** Starts (or, after the platform's drag-out, restarts) the drag with the pointer at [screen]. False (nothing changed) if no strip can take it. */
    fun begin(screen: Point): Boolean {
        targets.restart()
        homeBegun = false
        session = null
        runsOn = null
        inTransit = false
        val strip = targets.choose(screen) ?: home
        if (!enter(strip, screen)) return false
        // the drag (re)starts on another split's strip (taken back from the platform's drag out): the tab is out of its strip from the start
        if (strip !== home) beginTransit()
        return true
    }

    /**
     * The pointer moved to [screen]: the picture and the slot follow it, switching strips as the pointer crosses them. False if the
     * pointer is over no strip: only the picture moves then, and the caller decides between handing over and carrying on.
     */
    fun move(screen: Point): Boolean {
        val strip = targets.choose(screen)
        if (strip == null) {
            val target = editorTargetAt(screen)
            if (target != null) {
                overEditor(target, screen)
                return true
            }
        }
        else clearEditor()
        if (strip != null && strip !== runsOn) enter(strip, screen)
        val s = session
        if (s == null) {
            scroll.update(null, screen)
            return false
        }
        s.moveGhost(screen)
        if (strip == null) {
            scroll.update(null, screen)
            return false
        }
        s.pointerMoved(screen)
        scroll.update(runsOn, screen)
        return true
    }

    /** The session now runs on [strip], where the pointer is; the one before gives its strip back. False (nothing changed) if [strip] cannot take the tab. */
    private fun enter(strip: TabBarDnd, screen: Point): Boolean {
        val next = if (strip === home) homeSession else TabDragSession(strip, dragged, feedback)
        // back on the home strip: the tab is shown and selected again first, the home session starts from the strip as it began
        val cameBack = strip === home && inTransit
        if (cameBack) endTransit(show = true, reselect = true)
        val started = if (strip === home && homeBegun) next.resume(screen) else next.begin(screen)
        if (!started) {
            if (cameBack) beginTransit()
            targets.forget(strip)
            return false
        }
        val old = session
        if (old != null) {
            if (old === homeSession) {
                old.suspend()
                // the tab leaves its strip while the drag is on another one (the strip is as it began: suspended sessions restore it)
                if (strip !== home) beginTransit()
            }
            else {
                old.restoreOriginal()
                old.end()
                runsOn?.let { targets.forget(it) }
            }
        }
        if (strip === home) homeBegun = true
        session = next
        runsOn = strip
        return true
    }

    // ---- a bare editor area -------------------------------------------------------------------------------------------------

    /**
     * What a release at [screen] would do on an editor area that this drag handles itself, or null (the platform's, see [TabEditorDrop]):
     * the split under the pointer has no visible tab but the dragged one. The pointer is known to be off every strip.
     */
    private fun editorTargetAt(screen: Point): EditorTarget? {
        val hit = home.editorAreaAt(screen) ?: return null
        val shown = hit.dnd.tabs.tabs.filter { !it.isHidden }.mapNotNull { it.`object` as? VirtualFile }.filterNot { isPlaceholder(it) }
        val own = hit.dnd === home
        val landing = TabEditorDrop.resolve(TabEditorDrop.isBare(shown, dragged.file), hit.side, own, own && home.adapter.window.tabCount <= 1)
        return if (landing == TabEditorLanding.PLATFORM) null else EditorTarget(hit, landing)
    }

    /** The pointer at [screen] is off every strip and over a bare editor area (see [move]); the platform has no part in it. */
    fun overBareEditor(screen: Point): Boolean = editorTargetAt(screen) != null

    /**
     * The pointer is over the bare editor area [target]: the tab is out of its strip, the strips are as they began, the platform's drop
     * area is highlighted (the half for an edge, the whole area for the middle, as `JBTabsImpl.processDropOver` does) and the picture
     * follows the pointer.
     */
    private fun overEditor(target: EditorTarget, screen: Point) {
        leaveStrips()
        editor = target
        val hit = target.hit
        feedback.showDropArea(hit.dnd, hit.dnd.dropAreaScreen(hit.side))
        val size = feedback.size
        feedback.moveGhost(Point(screen.x - size.width / 2, screen.y - size.height / 2), hit.dnd)
        scroll.update(null, screen)
    }

    private fun clearEditor() {
        if (editor == null) return
        editor = null
        feedback.showDropArea(null, null)
    }

    /** No session runs: the one that did gives its strip back (as [enter] does) and the tab is out of its strip. */
    private fun leaveStrips() {
        val old = session
        if (old != null) {
            if (old === homeSession) old.suspend()
            else {
                old.restoreOriginal()
                old.end()
                runsOn?.let { targets.forget(it) }
            }
        }
        session = null
        runsOn = null
        if (!inTransit) beginTransit()
    }

    /**
     * The release over the bare editor area [target]: a new split on the edge's side with the tab (a move closes the source tab, keeping
     * its Reopen Closed Tab entry; a copy ([copy], Ctrl) keeps it), the tab into another bare split (middle), or nothing for the middle
     * of its own split (the tab comes back where it was, selected). A split that could not be made leaves everything as it was.
     */
    private fun dropOnEditor(target: EditorTarget, copy: Boolean) {
        leaveStrips()
        val file = dragged.file
        val hit = target.hit
        when (target.landing) {
            TabEditorLanding.NEW_SPLIT -> {
                val freezes = home.freezeOwn()
                val created = home.createSplitFor(file, hit.dnd, hit.side)
                if (created == null) {
                    releaseLater(freezes)
                    endTransit(show = true, reselect = true)
                    finish()
                    commitExpanded()
                    return
                }
                endTransit(show = copy, reselect = false)
                finish()
                LOG.info("tab drop on a bare editor edge: ${file.name}, side=${hit.side}, copy=$copy")
                try {
                    home.moveTabToCreatedSplit(file, dragged.pinned, created, copy, freezes)
                }
                catch (e: Exception) {
                    LOG.error("Moving tab ${file.name} to a new split failed", e)
                    releaseLater(freezes)
                }
            }
            TabEditorLanding.INTO_SPLIT -> {
                val other = hit.dnd
                val freezes = home.freezeStrips(other)
                // appended, as a drop at the end of its strip (a pinned tab goes first)
                val slot = if (dragged.pinned) 0 else TabDropPlanner.cellsOf(other.adapter.stripUnits(), file).size
                endTransit(show = copy, reselect = false)
                finish()
                LOG.info("tab drop on a bare editor: ${file.name} into another split, copy=$copy")
                try {
                    home.moveTabToSplit(file, dragged.pinned, other, Landing(slot, null, null), copy, freezes)
                }
                catch (e: Exception) {
                    LOG.error("Moving tab ${file.name} failed", e)
                    releaseLater(freezes)
                }
            }
            TabEditorLanding.STAY, TabEditorLanding.PLATFORM -> {
                endTransit(show = true, reselect = true)
                finish()
            }
        }
        commitExpanded()
    }

    private fun beginTransit() {
        inTransit = home.adapter.drag.beginTransit(dragged.file)
    }

    private fun endTransit(show: Boolean, reselect: Boolean) {
        if (!inTransit) return
        inTransit = false
        home.adapter.drag.endTransit(show, reselect)
    }

    // ---- release, cancel, hand-over ------------------------------------------------------------------------------------------

    /**
     * The release at [screen] (Ctrl held: [copy], the platform's copy modifier). The pointer position of the release itself is judged,
     * not only the last drag event. Ends the drag: the picture goes away and the result is in the model.
     */
    fun drop(screen: Point, copy: Boolean) {
        scroll.stop()
        val strip = targets.choose(screen)
        if (strip == null) {
            val target = editorTargetAt(screen)
            if (target != null) {
                dropOnEditor(target, copy)
                return
            }
        }
        if (strip != null && strip !== runsOn) enter(strip, screen)
        val s = session ?: return
        val outcome = s.finishDrop(screen)
        val target = runsOn
        val landing = outcome.applied?.landing
        if (target === home) {
            finish()
            s.commit(outcome)
            commitExpanded()
            return
        }
        if (target == null || landing == null) {
            s.restoreOriginal()
            endTransit(show = true, reselect = true)
            finish()
            commitExpanded()
            return
        }
        // both strips as they look at the release are frozen before the gap closes and the tab moves (see freezeStrips)
        val freezes = home.freezeStrips(target)
        s.clearPreview()
        // a move closes the hidden tab; a copy leaves it open: shown again, the selection stays where it went (the platform's dragOutFinished)
        endTransit(show = copy, reselect = false)
        finish()
        LOG.info("tab drop on another split: ${dragged.file.name}, copy=$copy")
        try {
            home.moveTabToSplit(dragged.file, dragged.pinned, target, landing, copy, freezes)
        }
        catch (e: Exception) {
            LOG.error("Moving tab ${dragged.file.name} failed", e)
            releaseLater(freezes)
        }
        commitExpanded()
    }

    /** The groups this drag expanded, in any strip, stay expanded ([TabBarDnd.commitExpandedByDrag]). */
    private fun commitExpanded() {
        for (strip in (home.peers() + home).distinct()) strip.commitExpandedByDrag()
    }

    /** Esc, or the tab bar vanishing: every strip goes back to the order and membership the drag started with. */
    fun cancel() {
        scroll.stop()
        session?.restoreOriginal()
        endTransit(show = true, reselect = true)
        finish()
        commitExpanded()
    }

    /** The pointer left every strip and the platform takes the drag: the strips go back, the picture goes away (the platform has its own). */
    fun handOver() = cancel()

    /** Ends every session and removes the picture; the strips are not touched. */
    fun finish() {
        scroll.release()
        session?.end()
        homeSession.end()
        session = null
        runsOn = null
        editor = null
        homeBegun = false
        feedback.clear()
    }

    /** The strip is going away: only timers are stopped (and a tab that is out of its strip comes back). */
    fun dispose() {
        endTransit(show = true, reselect = false)
        scroll.release()
        session?.stopTimers()
        homeSession.stopTimers()
    }
}
