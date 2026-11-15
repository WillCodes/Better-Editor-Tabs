package dev.willcodes.bettereditortabs.tabgroups.tabbar.ide

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.ui.tabs.impl.TabLabel
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader
import java.awt.event.ContainerEvent
import java.awt.event.ContainerListener
import java.beans.PropertyChangeListener
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.SwingUtilities

/**
 * For one split: reports the moment a preview tab becomes a normal tab ([promoted], `TabBarHost.tabMadePermanent`), exactly
 * once per promotion, whichever way the platform did it.
 *
 * The platform has no event for it. What it does when `EditorComposite.isPreview` goes false (a double click on the tab,
 * `EditorTabbedContainer.doProcessDoubleClick`; an edit, `FileEditorManagerImpl.fileUpdateChannel`; opening the file again without
 * preview, `EditorWindow.addComposite`; pinning, `EditorWindow.setFilePinned`) is the same in every path: it asks for the tab's title
 * and colour to be refreshed. The refresh drops the italic title attributes (`applyTabColor` in `EditorsSplitters.kt`, which reads
 * `composite.isPreview`) and the preview prefix of the tooltip (`FileEditorManagerImpl.getFileTooltipText`), both through
 * `TabInfo.setText` / `setTooltipText`, which fire `TabInfo.TEXT` on the tab's `changeSupport`. That property change is the signal:
 * it says "look at this tab again", and the answer is read from `EditorWindow.getComposite(file).isPreview`, compared with what the
 * tab was at the last look ([preview]).
 *
 * The look happens after the current event (the refresh is in the middle of the platform's own work, and the host may re-apply
 * groups), and is idempotent: any number of signals for one promotion, or none that matter, report once. A promotion that happens
 * while a drag runs ([busy]: ours or the platform's) is recorded but not reported. [expectPermanent] is for the host's own
 * `TabBar.makePermanent`: it is not reported either.
 *
 * Wraps `EditorWindow.getComposite`, `EditorComposite.isPreview` (public members of an internal class) and the tab bar's container
 * and `TabsListener`s. EDT only; disposed with the split's chain.
 */
internal class PreviewPromotion(
    private val window: EditorWindow,
    private val busy: () -> Boolean,
    private val promoted: (VirtualFile) -> Unit,
    parent: Disposable,
) : Disposable {
    private val tabs = StripReader.tabsOf(window)

    /** The files whose tab was a preview tab at the last look. */
    private var preview = HashSet<VirtualFile>()
    private var scheduled = false

    /** A signal arrived while a drag was running: the look it causes reports nothing. */
    private var quiet = false
    private var disposed = false

    private val watched: MutableSet<TabInfo> = Collections.newSetFromMap(WeakHashMap())
    private val signal = PropertyChangeListener { e -> if (e.propertyName == TabInfo.TEXT) onSignal() }
    private val labelListener = object : ContainerListener {
        override fun componentAdded(e: ContainerEvent) {
            val label = e.child as? TabLabel ?: return
            watch(label.info)
            // recorded at once: a tab that is opened as a preview tab and promoted within the same event is still a promotion
            (label.info.`object` as? VirtualFile)?.let { if (isPreview(it)) preview += it }
        }

        override fun componentRemoved(e: ContainerEvent) {}
    }

    init {
        Disposer.register(parent, this)
        tabs.addContainerListener(labelListener)
        for (info in tabs.tabs) {
            watch(info)
            (info.`object` as? VirtualFile)?.let { if (isPreview(it)) preview += it }
        }
        tabs.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                schedule()
            }

            override fun tabRemoved(tabToRemove: TabInfo) {
                (tabToRemove.`object` as? VirtualFile)?.let { preview.remove(it) }
            }
        }, this)
    }

    private fun isPreview(file: VirtualFile): Boolean = !window.isDisposed && window.getComposite(file)?.isPreview == true

    private fun watch(info: TabInfo) {
        if (watched.add(info)) info.changeSupport.addPropertyChangeListener(signal)
    }

    private fun onSignal() {
        if (disposed) return
        if (busy()) quiet = true
        schedule()
    }

    private fun schedule() {
        if (scheduled || disposed) return
        scheduled = true
        SwingUtilities.invokeLater {
            scheduled = false
            look()
        }
    }

    /**
     * The host is about to make [file]'s preview tab permanent itself (`TabBar.makePermanent`): that is not a promotion to report.
     * Call before changing the composite.
     */
    fun expectPermanent(file: VirtualFile) {
        preview.remove(file)
    }

    /** Compares every tab with its state at the last look; reports the ones that were preview tabs and are not any more. */
    fun look() {
        if (disposed || window.isDisposed) return
        val silent = quiet || busy()
        quiet = false
        val open = tabs.tabs.mapNotNull { it.`object` as? VirtualFile }
        val now = HashSet<VirtualFile>()
        for (file in open) if (isPreview(file)) now += file
        val gone = preview.filter { it in open && it !in now }
        preview = now
        if (!silent) for (file in gone) promoted(file)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        tabs.removeContainerListener(labelListener)
        for (info in watched.toList()) info.changeSupport.removePropertyChangeListener(signal)
        watched.clear()
    }
}
