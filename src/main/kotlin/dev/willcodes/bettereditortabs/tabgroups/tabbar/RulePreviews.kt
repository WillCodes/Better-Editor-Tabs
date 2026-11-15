package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.ThreadingAssertions

/**
 * POP-4 (rule preview): the outline of the tabs an auto-group rule matches, shown in every split's strip while the group popup edits the
 * rule. Remembers what was set (by window) so that a split that appears while the preview shows gets it too ([applyIfSet]). Purely
 * visual. EDT only.
 */
internal class RulePreviews(private val bars: () -> List<SplitBar>) {
    /** What [set] last set (by window), so that a split that appears while the preview shows gets it too; null = none. */
    private var rulePreview: Pair<Map<EditorWindow, Collection<VirtualFile>>, java.awt.Color?>? = null

    /** See [TabBar.setRulePreview]. */
    fun set(files: Map<SplitHandle, Collection<VirtualFile>>, color: java.awt.Color?) {
        ThreadingAssertions.assertEventDispatchThread()
        val on = color != null && files.values.any { it.isNotEmpty() }
        rulePreview = if (on) files.mapKeys { it.key.window } to color else null
        for (bar in bars()) apply(bar)
    }

    /** The bar was created: gives it the preview that shows now, if any. */
    fun applyIfSet(bar: SplitBar) {
        if (rulePreview != null) apply(bar)
    }

    /** The host is detached: the preview is gone. */
    fun clear() {
        rulePreview = null
    }

    private fun apply(bar: SplitBar) {
        val (files, color) = rulePreview ?: (emptyMap<EditorWindow, Collection<VirtualFile>>() to null)
        bar.adapter.setRuleOutline(files[bar.split.window].orEmpty(), color)
    }
}
