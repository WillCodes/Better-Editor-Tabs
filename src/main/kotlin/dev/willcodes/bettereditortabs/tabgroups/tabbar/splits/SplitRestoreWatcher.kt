package dev.willcodes.bettereditortabs.tabgroups.tabbar.splits

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.messages.Topic
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Fired once on the EDT after the restored editor layout has settled (see [SplitRestoreWatcher]). */
fun interface SplitsRestoredListener {
    fun splitsRestored(project: Project, splits: List<SplitInfo>)

    companion object {
        @JvmField
        val TOPIC: Topic<SplitsRestoredListener> = Topic(SplitsRestoredListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/**
 * Detects "the editors of this project are restored", then publishes [SplitsRestoredListener.TOPIC] and logs every
 * split key (INFO, so keys can be compared across restarts in idea.log).
 *
 * The platform has no public completion signal. Main-area restore is `EditorsSplitters.createEditors` (called from
 * `IdeProjectFrameAllocator.restoreEditors`, in parallel with project loading); detached windows are restored later, when
 * their `DockableEditorTabbedContainer.showNotify` calls `openFilesAsync`. Both finish in the internal
 * `finishStartupEditorRestore`, which notifies nobody. So this activity polls the layout (keys plus open file URLs of every
 * split) every [POLL_MS] and declares it restored once it is unchanged for [STABLE_POLLS] polls, no earlier than
 * [MIN_WAIT_MS] and no later than [MAX_WAIT_MS]. A very late detached window could be missed; listing again on demand
 * (`TabBar.splits()`) is always possible.
 */
class SplitRestoreWatcher : ProjectActivity {
    override suspend fun execute(project: Project) {
        // With the Classic UI nothing is restored, so the stored groups stay as they are for a later start in the New UI.
        if (!TabBar.isSupported) return
        val start = System.nanoTime()
        var previous: List<String>? = null
        var stable = 0
        while (true) {
            delay(POLL_MS)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val signature = withContext(Dispatchers.EDT) { signatureOf(project) } ?: return
            stable = if (signature == previous) stable + 1 else 0
            previous = signature
            val settled = stable >= STABLE_POLLS && elapsedMs >= MIN_WAIT_MS
            val nothingToRestore = signature.isEmpty() && elapsedMs >= EMPTY_WAIT_MS
            if (settled || nothingToRestore || elapsedMs >= MAX_WAIT_MS) {
                withContext(Dispatchers.EDT) { publish(project, elapsedMs) }
                return
            }
        }
    }

    private fun signatureOf(project: Project): List<String>? {
        if (project.isDisposed) return null
        return SplitIdentity.allSplits(project).map { split -> split.key.encode() + "=" + split.files.joinToString(",") { it.url } }
    }

    private fun publish(project: Project, elapsedMs: Long) {
        if (project.isDisposed) return
        val splits = SplitIdentity.allSplits(project)
        val log = thisLogger()
        log.info("Splits restored after about ${elapsedMs}ms: ${splits.size} split(s)")
        for (split in splits) {
            log.info("  split key=${split.key} detached=${split.isDetached} files=${split.files.joinToString { it.name }}")
        }
        project.messageBus.syncPublisher(SplitsRestoredListener.TOPIC).splitsRestored(project, splits)
    }

    private companion object {
        const val POLL_MS = 300L
        const val STABLE_POLLS = 5
        const val MIN_WAIT_MS = 2_500L
        const val EMPTY_WAIT_MS = 6_000L
        const val MAX_WAIT_MS = 30_000L
    }
}
