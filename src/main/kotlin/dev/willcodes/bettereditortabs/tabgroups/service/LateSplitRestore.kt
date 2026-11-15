package dev.willcodes.bettereditortabs.tabgroups.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Alarm
import dev.willcodes.bettereditortabs.tabgroups.model.persist.CurrentSplit
import dev.willcodes.bettereditortabs.tabgroups.model.persist.RestorePlan
import dev.willcodes.bettereditortabs.tabgroups.model.persist.RestorePlanner
import dev.willcodes.bettereditortabs.tabgroups.model.persist.StoredGroups
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.SplitInfo

/**
 * The startup restore for splits that appear after the first restore pass: a detached window that the platform restores after the editor layout
 * has settled (`DockableEditorTabbedContainer.showNotify` opens its files late).
 *
 * The stored splits of detached windows that no split claimed in the first pass stay *pending* for [PENDING_MS]. The main area's do
 * not: it is restored as a whole before the first pass, and with "Reopen files on startup" off nothing may survive. While they are
 * pending the saved state keeps them ([SavedGroups.pending]), so a save during that time loses nothing. A split that appears after the
 * first pass and has no instances is a *candidate*: once nothing changed in the candidates for [SETTLE_MS] (their files are still
 * opening), [check] matches the pending stored splits against them ([RestorePlanner]; the match needs shared files) and restores the
 * matches like the first pass ([apply]). Each candidate is looked at once. At the deadline what is left pending is dropped.
 *
 * The linked-file rule (a file grouped in one split joins its group when opened in another) waits for this: files opened in a candidate are held back ([tabsOpened]) and handed to [joinLinked] once the candidate has
 * been looked at (unless it was restored or got groups of its own), so a restored window is not first filled by it.
 *
 * Owned by [TabGroupsService]; EDT only.
 */
internal class LateSplitRestore(
    private val parent: Disposable,
    private val saved: SavedGroups,
    /** The live splits with their keys and files. */
    private val liveSplits: () -> List<SplitInfo>,
    /** True while [split] is a live split. */
    private val isLive: (SplitHandle) -> Boolean,
    /** True if [split] has group instances. */
    private val hasInstances: (SplitHandle) -> Boolean,
    /** Applies a restore plan made for the given splits (attach, order, instances, refresh without animation). */
    private val apply: (RestorePlan, List<SplitInfo>) -> Unit,
    /** The linked-file rule for files that were held back. */
    private val joinLinked: (SplitHandle, List<VirtualFile>) -> Unit,
) {
    private var firstPassSplits: Set<SplitHandle> = emptySet()
    private var deadline = 0L
    private val evaluated = HashSet<SplitHandle>()
    private val deferredOpened = LinkedHashMap<SplitHandle, MutableList<VirtualFile>>()
    private val settleAlarm by lazy { Alarm(Alarm.ThreadToUse.SWING_THREAD, parent) }
    private val expireAlarm by lazy { Alarm(Alarm.ThreadToUse.SWING_THREAD, parent) }

    /**
     * The first pass ran over [firstPass]; [pending] are the stored splits it left unclaimed (detached windows only). Starts the
     * waiting time if there are any. The caller captures the saved state afterwards.
     */
    fun start(firstPass: List<SplitInfo>, pending: StoredGroups) {
        firstPassSplits = firstPass.mapTo(HashSet()) { it.handle }
        evaluated.clear()
        if (pending.splits.isNotEmpty()) {
            deadline = System.currentTimeMillis() + PENDING_MS
            expireAlarm.cancelAllRequests()
            expireAlarm.addRequest({ expire() }, PENDING_MS.toInt())
        }
        saved.setPending(pending)
    }

    /** True while stored splits are still waiting for a late split. */
    private fun active(): Boolean = saved.pending.splits.isNotEmpty() && System.currentTimeMillis() < deadline

    /** A split [check] may still look at: it appeared after the first pass, was not looked at yet, and has no instances. */
    private fun isCandidate(split: SplitHandle): Boolean =
        active() && split !in firstPassSplits && split !in evaluated && !hasInstances(split)

    /** A split appeared: if it is a candidate, [check] runs once things have settled. */
    fun splitAppeared(split: SplitHandle) {
        if (isCandidate(split)) scheduleCheck()
    }

    /** Files were opened in [split]. True if they are held back (the split is a candidate); false: the caller applies the linked-file rule now. */
    fun tabsOpened(split: SplitHandle, files: List<VirtualFile>): Boolean {
        if (!isCandidate(split)) return false
        deferredOpened.getOrPut(split) { ArrayList() } += files
        scheduleCheck()
        return true
    }

    fun splitDisposed(split: SplitHandle) {
        deferredOpened.remove(split)
        evaluated.remove(split)
    }

    private fun scheduleCheck() {
        settleAlarm.cancelAllRequests()
        settleAlarm.addRequest({ check() }, SETTLE_MS.toInt())
    }

    /**
     * Matches the pending stored splits against the candidates, restores the matches and keeps the rest pending until the deadline.
     * The candidates count as evaluated afterwards, and their held-back files go through the linked-file rule unless they were restored.
     * Internal for tests; the alarm calls it after the debounce.
     */
    fun check() {
        if (saved.pending.splits.isEmpty()) return
        if (System.currentTimeMillis() >= deadline) {
            expire()
            return
        }
        val late = liveSplits().filter { isCandidate(it.handle) }
        if (late.isEmpty()) return
        val stored = saved.pending
        val current = late.map { s -> CurrentSplit(s.key, s.files.map { it.url }) }
        val plan = RestorePlanner.plan(stored, current)
        saved.setPending(RestorePlanner.pending(stored, current, detachedOnly = true))
        apply(plan, late)
        saved.capture()
        evaluated += late.map { it.handle }
        flushDeferred()
        if (saved.pending.splits.isEmpty()) end()
    }

    /** The waiting time is over: the stored splits nobody claimed are dropped, and the linked-file rule gets the files it was holding back. */
    private fun expire() {
        if (saved.pending.splits.isEmpty()) return
        end()
        saved.setPending(StoredGroups.EMPTY)
        flushDeferred()
    }

    private fun end() {
        deadline = 0L
        settleAlarm.cancelAllRequests()
        expireAlarm.cancelAllRequests()
        flushDeferred()
        firstPassSplits = emptySet()
        evaluated.clear()
    }

    /** The linked-file rule for the held-back files of splits that are no candidates any more; a split with groups of its own now is left alone. */
    private fun flushDeferred() {
        val ready = deferredOpened.keys.filter { !isCandidate(it) }
        for (split in ready) {
            val files = deferredOpened.remove(split).orEmpty()
            if (!isLive(split) || hasInstances(split)) continue
            joinLinked(split, files)
        }
    }

    internal companion object {
        /** How long stored splits that no live split claimed wait for a late one (milliseconds); a var for tests. */
        @JvmField
        var PENDING_MS = 60_000L

        /** A late split is looked at when nothing changed in the candidates for this long (milliseconds); a var for tests. */
        @JvmField
        var SETTLE_MS = 1_000L
    }
}
