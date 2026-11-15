package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Condition
import com.intellij.util.BooleanFunction
import dev.willcodes.bettereditortabs.tabgroups.model.groups.DeleteTrigger
import dev.willcodes.bettereditortabs.tabgroups.service.TabGroupsService
import dev.willcodes.bettereditortabs.tabgroups.tabbar.HeaderRef
import dev.willcodes.bettereditortabs.tabgroups.tabbar.SplitHandle
import dev.willcodes.bettereditortabs.tabgroups.tabbar.TabBar
import java.awt.event.KeyEvent

/**
 * The group popup (§6): the only place a group is renamed, recoloured, ungrouped or deleted from. Opens next to its header (below it for a strip on top; see
 * [PopupPlacement]) with the name field focused, all text selected. Closes on Enter, Escape or a click outside (POP-1); name and
 * colour are applied while editing, the auto-group rule (Advanced, RULE-1) is committed when the popup closes (see [create]). See
 * [GroupPopupContent] for the layout and the keyboard navigation.
 *
 * **The popup and its own child windows.** The platform closes a popup on a click outside it (`StackingPopupDispatcherImpl.dispatchMouseEvent`)
 * and when focus leaves the IDE (`IdePopupManager.maybeCloseAllPopups`). A click in a window that is a descendant of the popup's own window
 * (the heavyweight drop-down list of a combo box, whose owner is the popup window) is not "outside" there (`SwingUtilities.isDescendingFrom(window,
 * popup.getPopupWindow())`), a list that stays inside the popup window is within the content bounds, and `IdePopupManager` only closes popups
 * when focus leaves the IDE frame's window tree, which a chooser owned by the popup's window does not. The one thing that could still close
 * the popup behind the folder chooser's back is another route through `AbstractPopup.canClose`, so [create] sets a cancel callback that
 * refuses while [GroupPopupContent.childWindowOpen]. A real click outside the popup still closes it: nothing else is changed.
 */
object GroupPopup {
    /** INT-4: the popup for the group of [header] (a right click on it), next to the header. */
    fun show(project: Project, header: HeaderRef) {
        val popup = create(project, header.split, header.groupId) ?: return
        popup.show(PopupPlacement.beside(header, popup))
    }

    /**
     * CTX-1: the popup for the group [groupId] that was just created in [split]. The header is placed by a layout pass, which
     * [TabBar.header] runs; if it is still not showing, one more try follows in `invokeLater`, then the best position for [dataContext]
     * (or the middle of the window without one).
     */
    fun showForNewGroup(project: Project, split: SplitHandle, groupId: String, dataContext: DataContext?) {
        val tabBar = project.service<TabBar>()
        val header = tabBar.header(split, groupId)
        val popup = create(project, split, groupId) ?: return
        if (header != null) {
            popup.show(PopupPlacement.beside(header, popup))
            return
        }
        ApplicationManager.getApplication().invokeLater(
            {
                val late = tabBar.header(split, groupId)
                when {
                    late != null -> popup.show(PopupPlacement.beside(late, popup))
                    dataContext != null -> popup.showInBestPositionFor(dataContext)
                    else -> popup.showCenteredInCurrentWindow(project)
                }
            },
            Condition<Any?> { project.isDisposed },
        )
    }

    private fun create(project: Project, split: SplitHandle, groupId: String): JBPopup? {
        val service = project.service<TabGroupsService>()
        val group = service.group(groupId) ?: return null
        val previousRule = group.rule
        var popup: JBPopup? = null
        var committed = false
        lateinit var content: GroupPopupContent

        // POP-1, POP-2: the rule is committed exactly once, when the popup closes; the service asks about the matching open tabs
        // (POP-2) in a dialog, which must not open on a closing popup, hence invokeLater. offerMatching is false when the popup
        // closed because one of its actions was chosen.
        // POP-4: the preview ends with the popup. MEM-5 (amended): after a close by Enter / Escape / a click outside, the service asks
        // whether to keep the group if it is empty and has no enabled rule (groupPopupClosed, after the rule is applied).
        // POP-2 (amended 2026-11-14, user request): offered again on every normal close while Advanced is open, also with the rule
        // unchanged; with Advanced collapsed only when the rule changed.
        fun commit(closedNormally: Boolean) {
            if (committed) return
            committed = true
            val rule = content.editedRule()
            val changed = rule != previousRule
            val offer = closedNormally && (changed || content.advanced.expanded)
            if (!closedNormally) service.previewRule(groupId, null)
            ApplicationManager.getApplication().invokeLater(
                {
                    if (changed || offer) service.applyRule(groupId, rule, offer)
                    if (closedNormally) service.groupPopupClosed(split, groupId)
                },
                Condition<Any?> { project.isDisposed },
            )
        }

        // The rule is applied first (so Stash Group snapshots the edited rule), then the popup closes, then the action runs, so a
        // confirmation dialog never opens on top of a closing popup. Both run in invokeLater, in this order.
        fun closeThen(action: () -> Unit) {
            commit(false)
            popup?.closeOk(null)
            ApplicationManager.getApplication().invokeLater(action, Condition<Any?> { project.isDisposed })
        }

        // PopupPlacement: a popup opened above its header (Bottom placement) or beside it would grow downwards when re-packed, over the
        // header and past the screen's end, where packing cuts it off (user report). So it is moved first, to where its new size fits
        // next to the header as it is now (a new group's header may have been placed late), and packed afterwards; the content is
        // laid out before either, so no half-laid-out frame shows.
        fun repack() {
            val current = popup ?: return
            content.component.revalidate()
            content.component.validate()
            if (current.isVisible) {
                project.service<TabBar>().header(split, groupId)?.let { header ->
                    current.setLocation(PopupPlacement.beside(header, current).screenPoint)
                }
            }
            current.pack(true, true)
        }

        content = GroupPopupContent(
            project,
            group,
            service,
            GroupPopupActions(
                close = { popup?.closeOk(null) },
                splitRight = { closeThen { service.splitGroup(split, groupId, down = false) } },
                splitDown = { closeThen { service.splitGroup(split, groupId, down = true) } },
                // a split needs a tab: an empty group cannot be split
                canSplit = project.service<TabBar>().let { bar -> bar.logicalOrder(split).any { bar.groupIdOf(split, it) == groupId } },
                ungroup = { closeThen { service.ungroup(split, groupId) } },
                stashGroup = { closeThen { service.stashGroup(split, groupId) } },
                delete = { closeThen { service.requestDelete(split, groupId, DeleteTrigger.MENU) } },
                repack = ::repack,
            ),
        )
        val created = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content.component, content.nameField)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(false)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(true)
            // not while the folder chooser is open (see the class comment)
            .setCancelCallback(Computable { !content.childWindowOpen })
            // Escape closes an open drop-down list first, not the popup
            .setKeyEventHandler(BooleanFunction<KeyEvent> { e -> e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE && e.modifiersEx == 0 && content.rules.hideOpenList() })
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) = commit(true)
            })
            .createPopup()
        popup = created
        content.previewNow() // POP-4: a group that has a rule shows what it matches as soon as Advanced is open
        return created
    }
}
