package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.ide.ui.UISettings
import com.intellij.ide.ui.UISettingsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.ui.NewUI
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.ThreadingAssertions
import dev.willcodes.bettereditortabs.tabgroups.tabbar.drag.TabBarDnd
import dev.willcodes.bettereditortabs.tabgroups.tabbar.ide.GroupReopen
import dev.willcodes.bettereditortabs.tabgroups.tabbar.ide.TabClose
import dev.willcodes.bettereditortabs.tabgroups.tabbar.platform.PlatformInternals
import dev.willcodes.bettereditortabs.tabgroups.tabbar.splits.TemporarySplits
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripReader
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.StripUnit
import dev.willcodes.bettereditortabs.tabgroups.tabbar.strip.TabBarAdapter

/**
 * The tab bar facade: the one object the rest of the plugin uses to put groups into the editor tab bars and to hear about gestures.
 *
 * # Architecture
 *
 * ```
 * tabgroups/
 *   model/             pure Kotlin, no com.intellij import: drop rules, slot selection, pill hosting, order and placement rules,
 *                      close scope, split keys and matching, the stored form of the groups and the startup restore rules
 *                      (model/persist). Unit tested without the platform.
 *   tabbar/            the ONLY package that touches IDE-internal APIs (EditorWindow, JBTabsImpl, TabLabel, TabInfo, ...).
 *     TabBar           this facade (project service)          TabBarHost    the contract the service layer implements
 *     SplitRegistry    the per-split SplitBars, the focus history ([recentSplits]) and the per-bar dispatch of file events (sync, close, pin)
 *     RulePreviews     the rule preview outline in every strip, re-applied to a split that appears while it shows
 *     GroupTransfers   splitGroup, moveGroup, openInOrder, placeEmptyGroup: group moves and orders without a drag (they use the registry's bars)
 *     SplitHandle, GroupDecoration, HeaderRef, StripStyle     the public data types
 *     strip/           one adapter per split: collapse (hidden tabs) and its animation, pills hosted in tab labels, tint, drop slot,
 *                      empty state; StripDragSurface (`adapter.drag`): drag preview, gaps, transit,
 *                      the platform's drag state
 *     drag/            tab drag: TabDragController (the gesture, hand-over to and back from the platform's drag-out), CrossSplitTabDrag
 *                      (which strip, of any split, the pointer is over: TabDragTargets; the live work there), TabDragSession (one tab
 *                      on one strip: own gap and live re-order, or a foreign gap in another split's strip), TabDropPlanner (where it
 *                      lands), TabMove (moving the file to another split on a drop); group drag: HeaderDragHelper (gesture),
 *                      GroupDrag (the group is its pill while dragged, comes back expanded with the animation), ForeignGapSession,
 *                      GroupMove, and its targets that make the split first: GroupSplitMove (a new split at the edge of an editor area, a new
 *                      window outside every editor; EditorAreaTargets: the platform's drop side and drop-area highlight); DragAutoScroll
 *                      (edge auto-scroll and the wheel during a drag); GroupChipImage (the group's picture)
 *     strip/VerticalRows Left / Right placement: pills get rows of their own (labels moved after the layout); SideMoreButton shows the
 *                      platform's "more" button when only those rows make the strip overflow. Header refs carry the strip side
 *                      (StripSide.kt), which ui/popup/PopupPlacement uses to open popups on the side facing the editor
 *     strip/TabTransit a tab whose drag is over another split is out of its source strip, as in the platform's drag-out
 *     strip/CountPill  partial expand: a collapsed group can show one member beside its header (its peek, `GroupDecoration.peek`, a
 *                      visible tab right behind the header, tinted like a member) and the "+N" count of the members still hidden: a
 *                      small pill (`PillPainter.paintCount`, the look of the group drag chip's count) whose space is reserved in the
 *                      peek label's trailing side (Top / Bottom) or has a row of its own below the peek (Left / Right); a click on it is the
 *                      header's click. Header, peek and count are one unit for drops and `stripUnits()` lists them in strip order.
 *                      During a tab drag the pointer held for the hold time right behind a collapsed group's unit (behind its pill, or
 *                      its count) expands the group with the tab at its front, inside it; the group stays expanded, also
 *                      when the tab leaves it again or the drag is cancelled (`TabDragSession`, `TabBarDnd.expandedByDrag`, `DropRules.landing`)
 *     strip/CollapseController  also keeps the empty group instances: a group with an enabled rule whose tabs are all gone is shown
 *                      as its pill alone, one strip unit like a collapsed pill, at the place its tabs had (see "Empty instances" below)
 *     strip/StripSort  with "Sort tabs alphabetically" on, a strip that has groups is sorted by the plugin (pinned, groups by name,
 *                      ungrouped); the platform's view sorting is off for it, and the manual order comes back when it stops
 *     ide/             hooks into built-in behaviour: close actions, Reopen Closed Tab, tab limit,
 *                      new-tab placement, closing on behalf of group operations, the overflow ("more") list with group
 *                      headings (TabListOverride replaces the `TabList` action for strips with groups, OverflowList);
 *                      PreviewPromotion: reports a preview tab that becomes a normal tab ([TabBarHost.tabMadePermanent]);
 *                      GroupReopen: closing a group's tabs without Reopen Closed Tab entries (Stash Group, Delete Group: ClosedMark
 *                      tells whether the split's history top is still the entry of that close), opening files into a split in a given
 *                      order (bringing a group back) and picking the split the built-in Reopen Closed Tab acts on
 *     splits/          restart-stable split identity and the "splits restored" signal; TemporarySplits / PlaceholderFile:
 *                      the hidden placeholder tab that keeps a split alive while it holds only empty groups (see "Temporary splits" below)
 *     platform/        PlatformInternals: every reflective access, documented one by one, with a start-up self-check
 *   service/, ui/, actions/, rules/, settings/             callers: they may import only the public types of tabbar/ (and splits/)
 *     service/         TabGroupsService (state + operations, the TabBarHost, and the project's PersistentStateComponent in the
 *                      workspace file: GroupsPersistence converts the model to XML beans, the listeners restore at startup, save before
 *                      close and follow renames and moves), GroupComeback (the stash of closed groups, and the records of
 *                      deleted groups for Reopen Closed Tab; the rules of bringing a group back are model/groups/GroupRestore),
 *                      TabGroupsSettings; reaches popups and dialogs only through its
 *                      TabGroupsUi interface, which ui/ implements (ui/ -> service/, never the other way round)
 *     ui/popup/        the group popup: GroupPopup (open, place, close), GroupPopupContent (sections), PopupPlacement;
 *                      TabListPopup, the hover list of a collapsed group
 *     actions/         -> service/ and ui/
 * ```
 *
 * Rules:
 * - Only `tabbar/` touches internal platform API, and inside it only the sub-packages above do; everything in `tabbar/` except the
 *   public types listed in this KDoc, the split-identity types of `splits/` and the classes `plugin.xml` instantiates is `internal`.
 * - Reflection and access by class name live in `platform/PlatformInternals.kt` and nowhere else. Each hook fails soft and is listed
 *   by `PlatformInternals.selfCheck()`, which is logged once per IDE session when this service is created.
 *
 * # How a host plugs in
 *
 * A host implements [TabBarHost] and sets [host]. From then on this service owns one adapter, one drag layer and one new-tab placement
 * per editor split (created when a split appears, disposed with it): tab and group drag and drop work in every split without further
 * setup. The host answers [TabBarHost.groupsIn] with the groups of a split whenever it calls [refresh], and receives header gestures,
 * drag results, and hidden-tab selection and closed-file events. With [host] null the plugin is completely inert: no component is added to any tab bar, no
 * drag is taken over, and the built-in actions behave exactly like the platform's.
 * With the Classic UI ([isSupported] false) no host can attach, so the plugin stays inert there.
 *
 * # Splits
 *
 * Every split's new-tab placement ([TabBarHost.tabsOpened]) reports the files that are new in it, before it applies its own rule, so the
 * host can make a file that is grouped in another split join that group here; a split that appears after the host attached reports
 * the files it starts with, and the files our own drag code opens in a target split are acknowledged, not reported. [recentSplits]
 * orders the splits by focus (the host picks the winning group and the target of a group deletion with it); [moveGroup] moves a
 * whole group instance to another split like a drop would.
 *
 * # Persistence
 *
 * The tab bar stores nothing. The host asks [logicalOrder] for each split it saves (the order including hidden tabs of collapsed groups
 * at their headers), and at startup, once the `splits/` signal says the layout is restored, re-orders each split with [reorder] and
 * applies its groups with `refresh(split, animate = false)`: restoring is never animated.
 *
 * # Empty instances
 *
 * A group with an enabled auto-group rule keeps its instance when its last tab closes or leaves. The host then reports, through
 * [TabBarHost.groupsIn], a [GroupDecoration] none of whose members has a tab in the split (an empty list is the usual case), and the
 * strip shows it as its **pill alone**: it looks like a collapsed group's pill whatever its `collapsed` flag says, has no tab and no
 * tint, and is one strip unit (nothing is dropped into it; a tab goes in front of or behind it). The adapter treats such an
 * instance as a collapsed group with nothing hidden, so layout, geometry, hit units, side placements and the drag code need no
 * special case; what is new is only where it stands, which is kept like a collapsed group's place, as the tab it stands behind
 * (`CollapseController`): the place its tabs had when the last one went (the tab in front of its first tab as of the last quiet layout
 * pass, re-anchored when that tab closes; a drag's drop is the same), the end of the strip for an instance the strip never showed (restored
 * from the stash, Reopen Closed Tab of an empty group, a new split), or where a drag or the sort puts it (drags keep the order as a
 * sequence of tabs and empty pills, `model/strip/StripItem`; a tab dropped next to an empty pill keeps its side of it). It never
 * animates (the collapse animation is for flips of groups with open tabs; the first member of an empty instance appears at once, hidden if the group
 * is collapsed). An empty group that gets its first member(s) keeps its pill where it was and the
 * members stand right behind the pill, in front of whatever followed it, other empty pills included (`TabBarAdapter.seatFirstMembers`);
 * the slot right behind an empty pill is a trailing edge: a tab held there for the hold time joins the group, which is then
 * reported expanded (`PillCell.empty`, `DropRules.landing`; the slot in front is outside; a group drag never lands inside it, it is one unit);
 * a group whose only tab was dragged out (same strip or another split) keeps its pill where the tab was
 * (`TabDropPlanner.pillsKept`); a group the host no longer lists keeps no state here (`CollapseController.syncEmpty`) and is never reported
 * back (`SplitBar.groups`). [groupOrder] lists empty pills at their place, [logicalOrder] ignores them (no files), [header] answers for them
 * (the popup anchors to it), [headerIndex] says how many tabs stand in front of any header. An empty group is dragged like a collapsed
 * one: within its strip, onto another split's strip (a pill appears at the drop place: the host hears [TabBarHost.groupsChangedByDrag]
 * for the source and [TabBarHost.groupMovedIn] for the target, with an empty member list) or onto the middle of another split (appended);
 * an editor edge and outside every editor make a temporary split or window for it (a new split or window needs a file,
 * `EditorWindow.split` answers null for a window without a tab, so a placeholder tab is that file). An empty pill never stands inside another group's span:
 * when a group grows at its end, a pill that stood behind its old last tab stands behind the new one ([placeEmptyGroup] too).
 * Held at its own trailing slot, the only tab of a group leaves it like any member (`OwnGroupStay`), and the slot right
 * behind an empty pill in front of another group is a slot of its own (a pill is crossed as soon as the pointer enters it, like a tab: `DropSlots.select`). The overflow list
 * has no row for it. With "Sort tabs alphabetically" the pill stands among the groups by name. A split with no tab at all shows
 * the pill in its otherwise empty strip when the platform shows that strip. Limit: when a collapsed group's pill stood right behind
 * the last tab of a group that then empties through several closes, the two pills can swap places.
 *
 * # Temporary splits
 *
 * An editor split exists only while it has a tab, and `EditorWindow.split` / `openFileInNewWindow` need a file, so a split that holds only
 * empty groups is kept open by one **placeholder tab** (`splits/PlaceholderFile`, an in-memory `LightVirtualFile` that the platform keeps
 * out of Recent Files and navigation history), opened in the split and hidden at once (`TabInfo.isHidden`, as collapse hides tabs); the
 * strip shows only the pills and the empty-editor overlay covers the content ([isTemporary]). It is created by a drop of an empty
 * group's header on an editor edge or outside every editor (`GroupSplitMove`), and by **keep-alive**: when a split's last real tab is
 * about to close (`FileEditorManagerListener.Before.beforeFileClosed`, whatever the cause: close, group or tab move, stash, delete) while
 * it holds an empty group and would close ([TemporarySplits.wouldClose]: not the main area's last window), the placeholder is opened
 * first. It becomes an ordinary split when a real tab arrives (the placeholder is closed quietly) and ends when the host lists no group
 * for it any more. It is never reported: not as a tab opened, closed or made permanent, not in [visibleFiles], [logicalOrder] or the
 * drags (`StripReader.allFiles`), not in `SplitInfo.files`, not to the close actions (`CloseScoping`) or the tab limit
 * (`TabLimitHandler`), and the Reopen Closed Tab entry of its close is removed. When the user closes a temporary split that still
 * holds empty groups (its placeholder is closed, or the split is merged away or its window closed) the host hears it once through
 * [TabBarHost.temporarySplitClosing] (before the disposal when the platform allows, else right after) and moves or deletes the groups.
 * The saved layout must not mention a placeholder, so the host calls [closePlaceholders] when the project closes.
 *
 * # Preview promotion
 *
 * [TabBarHost.tabMadePermanent] is reported once whenever a split's preview tab becomes a normal tab, by whatever path: a double click,
 * an edit, the file opened again for good, pinning. The platform has no event; `ide/PreviewPromotion` watches the tabs' `TabInfo.TEXT`
 * property changes (the refresh every path triggers) and compares `EditorComposite.isPreview` with what it was at the last look. Not
 * reported for [makePermanent] (the host's own call) or while a drag runs.
 *
 * # Collapse animation
 *
 * [refresh] passes `animate` (and [StripStyle.animateCollapse]) down to the split's adapter; every other caller of
 * `TabBarAdapter.setGroups` (live re-ordering during drags, group moves) passes false, which snaps a running animation to its end. One
 * `strip/CollapseAnimator` per strip animates Top / Bottom strips by setting the member labels' preferred widths each frame, so the
 * platform's own layouts slide the following tabs (single and multi row); the tween is pure (`model/strip/CollapseAnimation`). While a
 * group collapses the adapter's `shown` view treats it as expanded: the selection leaves the group at the start, the tabs are
 * hidden (`TabInfo.isHidden`) only when the animation ends; an expand shows them at the start and grows them. A flip during the
 * animation reverses it from its current extent; selecting a tab of a collapsing group is reported through [TabBarHost.hiddenTabSelected], so the host expands it.
 * Drags call `TabBarAdapter.finishCollapseAnimations` before they read the strip, and drag results refresh without animation. Left /
 * Right strips animate heights: the platform fixes label heights there, so `strip/VerticalRows` draws animating labels shorter after
 * the layout and moves the labels behind them up.
 *
 * # Partial expand
 *
 * A collapsed [GroupDecoration] may carry a `peek`: the one member shown. `GroupsApplier.shown` normalises it (an open member of a collapsed
 * group, else none); `CollapseController` hides every other member and keeps the peek as an ordinary visible tab right behind the group's
 * anchor, so the group's place, pill rank and the logical order ([logicalOrder] keeps the member order, the peek among them) are those
 * of a full collapse; the peek hosts its header's pill (leading) and its count pill (trailing, `PillLayout`). Navigating to a hidden member
 * is reported through [TabBarHost.hiddenTabSelected]; the host answers with a decoration whose peek is that file and the strip un-hides
 * and selects it (the old peek hides again). Animation: only the tabs that change move (`PartialExpand.change`): the peek grows out of the
 * pill, the rest grows on a full expand, the peek shrinks on a collapse; a moved peek is instant. Drags: the peek is dragged like a member
 * (it leaves the group when dropped outside the unit, `TabDragSession`), a header drag carries the whole group and arrives collapsed
 * without a peek, drag results report the peek only while the group is still partial (`TabDropPlanner.groupsAfterDrop`).
 *
 * # Threading
 *
 * Everything runs on the EDT (Swing state). The one exception is [groupIdOf], which the tab-limit handler may call off the EDT: it
 * answers from an immutable index that is swapped as a whole every time the groups of a split are applied. [TabBarHost.canReopenClosedGroup]
 * is likewise called from action updates and must be thread-safe.
 *
 * # Disposal chain
 *
 * `Project` -> [TabBar] -> `SplitBar` (per split) -> `TabBarAdapter` -> `TabBarDnd` (-> drag controller (and its drag session), drag overlay, new-tab placement,
 * glass-pane observers) and the adapter's own collaborators (tint, listeners). A split's chain is disposed when its window goes away
 * and when [host] is detached; disposing the adapter shows hidden tabs again and removes every component it added to the tab bar.
 */
@Service(Service.Level.PROJECT)
class TabBar(private val project: Project) : Disposable {
    companion object {
        /**
         * The plugin works only in the New UI: with the Classic UI no host attaches, so the tab bar is never touched and the overridden
         * actions behave like the built-in ones. Switching the UI needs a restart, so this does not change during a session.
         */
        @JvmStatic
        val isSupported: Boolean get() = NewUI.isEnabled()
    }

    /** The placeholder tabs that keep splits holding only empty groups alive ([TemporarySplits]). */
    internal val temporary = TemporarySplits(project, this)

    /** The rule preview outline in the strips. */
    private val previews: RulePreviews = RulePreviews { registry.bars() }

    /** The per-split bars, the focus history and the per-bar dispatch of file events. */
    private val registry: SplitRegistry = SplitRegistry(project, this, this, temporary, { host }) { previews.applyIfSet(it) }

    /** The group moves and orders that open files without a drag. */
    private val transfers: GroupTransfers = GroupTransfers(project, this, registry)

    /**
     * The host the tab bar reports to and pulls groups from; null = the plugin is inert. Setting it attaches (every current split gets
     * its adapter, drag layer and placement, and [TabBarHost.splitAppeared] is called for each) or detaches (all of that is
     * disposed; the host gets no callbacks for it). EDT only.
     */
    @Volatile
    var host: TabBarHost? = null
        set(value) {
            ThreadingAssertions.assertEventDispatchThread()
            if (field === value || (value != null && !isSupported)) return
            if (field != null) detach()
            field = value
            if (value != null) {
                registry.attaching = true
                try {
                    sync()
                }
                finally {
                    registry.attaching = false
                }
            }
        }

    /**
     * How headers and tinted tabs look; [StripStyle.DEFAULT] until the host sets it. Setting it re-applies the style to every
     * split at once: headers re-measure (the name is cut at the new length, the pill's reserved width changes, the strip is laid out
     * again) and repaint, tints repaint with the new opacity. Works with or without a host. EDT only.
     */
    var style: StripStyle = StripStyle.DEFAULT
        set(value) {
            ThreadingAssertions.assertEventDispatchThread()
            if (field == value) return
            field = value
            for (bar in registry.bars()) bar.adapter.restyle()
        }

    init {
        PlatformInternals.logSelfCheckOnce()
        // Programmatic subscription: no plugin.xml entry needed.
        // "Sort tabs alphabetically" is switched on or off (application setting, so the application bus)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            UISettingsListener.TOPIC,
            object : UISettingsListener {
                override fun uiSettingsChanged(uiSettings: UISettings) {
                    for (bar in registry.bars()) bar.adapter.uiSettingsChanged()
                }
            },
        )
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.Before.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener.Before {
                override fun beforeFileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (registry.isEmpty()) return
                    temporary.beforeFileClosed(file) // keep-alive, and the user closing a temporary split
                    for (bar in registry.bars()) bar.adapter.beforeFileClosed(file)
                }
            },
        )
    }

    // ---- groups --------------------------------------------------------------------------------------------------------

    /**
     * Pulls [TabBarHost.groupsIn] for [split] and applies it to the strip (headers, collapse, tint). No-op without a host.
     *
     * A group whose collapsed flag changed since the last refresh collapses or expands with an animation when [animate] is
     * true and [style] allows it ([StripStyle.animateCollapse]); a change during a running animation reverses it from where it
     * is. Selection changes happen at once, never at the end of the animation. Pass false where nothing may
     * move (restoring groups at startup, a placement change).
     */
    fun refresh(split: SplitHandle, animate: Boolean = true) {
        val h = host ?: return
        val bar = registry.barFor(split) ?: return
        bar.apply(h.groupsIn(split), animate && style.animateCollapse)
        temporary.schedule(split.window) // a temporary split whose last group went ends
    }

    /**
     * [refresh] after a drag put a group back: [expanding] is a group that was dragged as its collapsed header and comes back expanded;
     * only it plays the expand animation (if [style] allows it), every other flag is applied as it is. Null: a
     * plain drag result, nothing animates.
     */
    internal fun refreshDropped(split: SplitHandle, expanding: String?) {
        val h = host ?: return
        val bar = registry.barFor(split) ?: return
        bar.apply(h.groupsIn(split), expanding != null && style.animateCollapse, expanding)
        temporary.schedule(split.window)
    }

    /** [refresh] for every split. */
    fun refreshAll() {
        if (host == null) return
        sync()
        for (bar in registry.bars()) refresh(bar.split)
    }

    /**
     * The id of the group [file] belongs to in [split], per the groups last applied there; null if ungrouped or without a host. Used
     * by the IDE hooks. Safe to call from any thread.
     */
    fun groupIdOf(split: SplitHandle, file: VirtualFile): String? {
        if (host == null) return null
        return registry[split.window]?.groupIdOf(file)
    }

    /**
     * The header of group [groupId] in [split] if it is currently showing (it has a visible area in the strip), else null (unknown group,
     * scrolled out, no host). Validates the tab component first, so it may be called right after [refresh]: a header is placed in the
     * layout pass that follows a change, and this call runs that pass now. (A strip that has no size yet, because it is not displayed,
     * has nothing to place and answers null.) For anchoring a popup.
     */
    fun header(split: SplitHandle, groupId: String): HeaderRef? {
        if (host == null) return null
        val component = registry[split.window]?.adapter?.showingHeader(groupId) ?: return null
        return HeaderRef(split, groupId, component, component.pillBounds(), StripReader.tabsOf(split.window).stripSide())
    }

    /**
     * The ids of the groups of [split] in the order their headers stand in the strip (collapsed groups at their place), as laid out
     * from the groups last applied. [TabBarHost.groupsIn] must list a split's groups in this order: where two pills share a host tab
     * (collapsed groups next to each other or in front of another group) their order comes from that list. A host that adds a group
     * by other means than a drag (which reports the new order itself) calls [refresh], reads this and refreshes again if its own
     * order differs. Groups without a pill in the strip (no tab at all) are left out. Empty without a host.
     */
    fun groupOrder(split: SplitHandle): List<String> {
        if (host == null) return emptyList()
        val adapter = registry[split.window]?.adapter ?: return emptyList()
        return adapter.stripUnits().mapNotNull { (it as? StripUnit.Pill)?.groupId }.distinct()
    }

    /**
     * Where [groupId]'s header stands in [split]: the number of tabs of [logicalOrder] in front of it. For a group with open tabs that is
     * the index of its first member; for an *empty* instance (a group with an enabled rule whose tabs are all gone, shown as its
     * pill alone) it is the place the pill keeps: the number of tabs of [logicalOrder] in front of the pill. Several empty pills standing
     * together all answer the same number (the tabs in front of the run of pills; they have no tabs between them), so the number says
     * where members of any of them go: a tab opened at that index and made a member is seated by the strip right behind its group's
     * pill and in front of the other pills (`TabBarAdapter.seatFirstMembers`), wherever it was opened. Null if the split shows
     * no header for the group, or without a host. Auto-group rules and the group popup place new members with it. EDT only.
     */
    fun headerIndex(split: SplitHandle, groupId: String): Int? {
        if (host == null || split.window.isDisposed) return null
        return registry[split.window]?.adapter?.headerIndex(groupId)
    }

    /**
     * Rule preview: outlines the tabs of [files] in each split's strip in [color] (the group's colour)
     * while the group popup edits an auto-group rule, so the user sees which open tabs the rule matches. Replaces the previous preview
     * as a whole; an empty map (or a null [color]) clears it. Purely visual: membership, tint and order are unchanged. Tabs hidden by
     * collapsed groups are not outlined. EDT.
     */
    fun setRulePreview(files: Map<SplitHandle, Collection<VirtualFile>>, color: java.awt.Color?) {
        previews.set(files, color)
    }

    /**
     * Group popup *Split Right* / *Split Down*: opens a new split beside [split] (to the right, or below if
     * [down]) holding a linked copy of [groupId]'s instance: the group's open files open there in order and join the same group
     * (as a Ctrl-drop of the header on the editor's right or bottom edge does); the source keeps its instance. The new split becomes
     * the current one. False (nothing done) without a host, for an unknown group or one with no open tab (a split needs a tab). EDT.
     */
    fun splitGroup(split: SplitHandle, groupId: String, down: Boolean): Boolean = transfers.splitGroup(split, groupId, down)

    /**
     * Puts the pill of [groupId]'s *empty* instance in [split] behind the first [index] tabs of [logicalOrder]
     * (0 = in front of every tab; past the end = at the end), as the restore found it stored. Call after the [refresh] that brought the
     * empty instance in. No-op without a host or for a group that is not empty there. EDT.
     */
    fun placeEmptyGroup(split: SplitHandle, groupId: String, index: Int) = transfers.placeEmptyGroup(split, groupId, index)

    // ---- splits and tabs --------------------------------------------------------------------------------------------------

    /** Every editor split of the project (main area and detached windows). */
    fun splits(): List<SplitHandle> = FileEditorManagerEx.getInstanceEx(project).windows.map { SplitHandle(it) }

    /**
     * Every live split (main area and detached windows), the most recently focused first (the group of the most recently
     * focused split wins; a deleted group's tabs are merged into the instance in the first of them that has one). Splits that were never seen focused follow in their stable
     * [splits] order. The platform has no public "focus history of windows" (its `currentWindowFlow` is internal), so it is tracked
     * here: the current window (`FileEditorManagerEx.currentWindow`, the active container's current window) is noted on every
     * selection change, file open and close ([sync], which [TabBarEditorListener] calls; the platform fires a selection change when
     * the user moves to another split, because every split has its own editor) and again right here. Tracking starts when the host
     * attaches. EDT only.
     */
    fun recentSplits(): List<SplitHandle> = registry.recentSplits()

    /** The split an action was invoked in: the tab bar the event comes from, else the current split. */
    fun splitFor(e: AnActionEvent): SplitHandle? {
        val window = e.getData(EditorWindow.DATA_KEY) ?: FileEditorManagerEx.getInstanceEx(project).currentWindow
        return window?.let { SplitHandle(it) }
    }

    /**
     * Selects the tab of [file] in [split] (and focuses its editor if [focus]), as clicking the tab would. A tab hidden by a collapsed
     * group is selected too; the strip then reports it as a hidden-tab selection ([TabBarHost.hiddenTabSelected]), so a caller that has just expanded
     * the group itself should expand first and select afterwards. No-op if [file] is not open in [split].
     */
    fun selectTab(split: SplitHandle, file: VirtualFile, focus: Boolean) {
        if (split.window.isDisposed) return
        if (TabClose.openTabs(split.window, listOf(file)).isEmpty()) return
        split.window.setSelectedComposite(file, focus)
    }

    /** Files of the tabs currently shown in [split]'s strip, in order (tabs hidden by collapsed groups excluded). */
    fun visibleFiles(split: SplitHandle): List<VirtualFile> = StripReader.visibleFiles(StripReader.tabsOf(split.window))

    /**
     * Every open tab of [split] in the order the user sees it: [visibleFiles], with the tabs hidden by each collapsed group put at the
     * place of the group's header (this is the order to store, because the platform keeps hidden tabs at the end of its own list
     * and would reopen them there). Built from the adapter's own bookkeeping, so it needs no laid-out strip; where two collapsed groups
     * stand together it agrees with [groupOrder]. Without a host (or an adapter) nothing is hidden and it is the platform's tab order.
     */
    fun logicalOrder(split: SplitHandle): List<VirtualFile> {
        if (split.window.isDisposed) return emptyList()
        val adapter = if (host == null) null else registry[split.window]?.adapter
        return adapter?.logicalOrder() ?: StripReader.allFiles(StripReader.tabsOf(split.window))
    }

    /** The selected file of [split], or null if nothing is selected / every tab is hidden. */
    fun selectedFile(split: SplitHandle): VirtualFile? = StripReader.selectedFile(StripReader.tabsOf(split.window))

    /**
     * Re-orders the visible tabs of [split] to [visibleOrder] (menu operations, e.g. putting a tab next to its new group). Tabs hidden by
     * collapsed groups are not part of the order: they keep their places (behind the visible tabs, each collapsed group keeping its
     * header position), so pass only what [visibleFiles] returns. Files of [visibleOrder] that are not visible are ignored; visible
     * tabs it omits follow the listed ones in their current order. The platform may still put pinned tabs first (see
     * [TabBarHost.tabPinned]). Needs the split's adapter, i.e. an attached [host]; without one it is a no-op, so that an inert plugin
     * never touches the tab order. Call [refresh] afterwards if the groups' member order changed.
     */
    fun reorder(split: SplitHandle, visibleOrder: List<VirtualFile>) {
        if (host == null) return
        registry[split.window]?.adapter?.reorderVisible(visibleOrder)
    }

    /**
     * While the plugin sorts [split] alphabetically, the manual order the strip goes back to when sorting is switched off (or
     * its last group goes); null otherwise. The host stores it so that it survives a restart. Without a host null.
     */
    fun manualOrder(split: SplitHandle): List<VirtualFile>? = if (host == null) null else registry[split.window]?.adapter?.manualOrder()

    /**
     * [files] is [split]'s manual order as stored before a restart. Call it before the split's groups are refreshed: when the
     * plugin starts sorting the split it remembers this order instead of the current (sorted) one; when sorting is off, the next
     * refresh puts the tabs in this order. Needs an attached [host]; a no-op otherwise.
     */
    fun setManualOrder(split: SplitHandle, files: List<VirtualFile>) {
        if (host == null) return
        registry.barFor(split)?.adapter?.seedManualOrder(files)
    }

    /** True if [file] has a pinned tab in [split]; false if it is not open there. */
    fun isPinned(split: SplitHandle, file: VirtualFile): Boolean {
        if (split.window.isDisposed) return false
        return split.window.getComposite(file)?.isPinned ?: false
    }

    /** True if [file]'s tab in [split] is a preview tab (italic title, replaced by the next single-click open); false if it is not open there. */
    fun isPreview(split: SplitHandle, file: VirtualFile): Boolean {
        if (split.window.isDisposed) return false
        return split.window.getComposite(file)?.isPreview ?: false
    }

    /**
     * Turns the preview tab of [file] in [split] into a normal tab, as a double click on it does (`doProcessDoubleClick` in
     * `EditorTabbedContainer`: `composite.isPreview = false`, then a refresh of the tab's colours and title). The platform refreshes the
     * presentation through `scheduleUpdateFileColor`, which a plugin cannot call, so this uses the public
     * `FileEditorManagerEx.updateFileColor`, which schedules the same update for every split showing the file (it ends in `applyTabColor`,
     * which draws the italic title only while the composite is a preview). The refresh is asynchronous. No-op if the tab is not a preview
     * tab or not open there. Each split has its own composite, so other splits showing the file are unaffected.
     */
    fun makePermanent(split: SplitHandle, file: VirtualFile) {
        if (split.window.isDisposed) return
        val composite = split.window.getComposite(file) ?: return
        if (!composite.isPreview) return
        registry[split.window]?.promotion?.expectPermanent(file) // our own call: not a promotion to report
        composite.isPreview = false
        FileEditorManagerEx.getInstanceEx(project).updateFileColor(file)
    }

    /** The right-clicked tab if a tab context menu is showing in [split], otherwise the selected tab. */
    fun contextFile(split: SplitHandle): VirtualFile? = StripReader.contextFile(split.window)

    /** The subset of [files] that is open in [split] (tabs hidden by collapsed groups included), in the given order. */
    fun openTabs(split: SplitHandle, files: List<VirtualFile>): List<VirtualFile> = TabClose.openTabs(split.window, files)

    /** Closes [files] in [split] the normal way: each one is added to Reopen Closed Tab history. */
    fun closeTabs(split: SplitHandle, files: List<VirtualFile>) = TabClose.closeTabs(split.window, files)

    /**
     * Closes [files] in [split] without Reopen Closed Tab entries (Stash Group: the stash and the closed-group records
     * are how a group comes back; an entry that the platform's own close added is removed again, see [TabClose.closeForMove]; if the
     * history cannot be read the entries stay, which is only cosmetic). Files that are not open are skipped; a split left without tabs
     * closes as with a normal close. Returns a mark of the close for [isMostRecentClose].
     */
    fun closeTabsQuietly(split: SplitHandle, files: List<VirtualFile>): ClosedMark = GroupReopen.closeQuietly(split.window, files)

    /**
     * True if nothing has been closed or reopened (Reopen Closed Tab history) in [mark]'s split since the close that made it, so
     * the Reopen Closed Tab that comes next belongs to that close. False once the split is gone. If the history cannot be read
     * (platform change) it answers true: the record is offered first (fail soft).
     */
    fun isMostRecentClose(mark: ClosedMark): Boolean = GroupReopen.isMostRecent(mark)

    /**
     * The split the built-in Reopen Closed Tab acts on for [e]: the current window of the editor splitters around the action's
     * context component, else the current window; null if the project has no split.
     */
    fun reopenSplit(e: AnActionEvent): SplitHandle? = GroupReopen.reopenWindow(project, e)?.let { SplitHandle(it) }

    /**
     * Opens [toOpen] in [split] as a block and puts the split's tabs in [order] (bringing a group back). [order] is the
     * whole logical order wanted (as [logicalOrder] lists it, with the new files where they belong); the files of [toOpen] open in the
     * background (neither selected nor focused), files that are open already only move. Tabs hidden by collapsed groups are shown for
     * the moment, without an animation; the caller then applies the new membership and calls [refresh], which hides them again (no
     * collapsed flag flips, so nothing animates). The opening is the strip's own change (the platform's selection churn is not a user selection)
     * and the opened files are acknowledged: [TabBarHost.tabsOpened] is not called for them and the new-tab placement leaves them where they are.
     *
     * [split] null: the project has no editor split; the first file is opened with `FileEditorManagerEx.openFile(file, null, options)` (synchronous, as the group moves use it) so that the platform makes a
     * window, and the rest follows there. Returns the split used and the files of [toOpen] that have a tab there, or null (nothing
     * done) without a host, for a split that is gone, or when nothing could be opened into a split-less project. EDT only.
     */
    fun openInOrder(split: SplitHandle?, toOpen: List<VirtualFile>, order: List<VirtualFile>): OpenedBlock? =
        transfers.openInOrder(split, toOpen, order)

    /**
     * Moves the whole instance of group [groupId] from [from] to [to] the way a group drop on the other split's strip does: the tabs move (no Reopen Closed Tab history), merged into [to]'s instance of the same group, appended at its end (a new
     * instance at the end of the strip if it has none), and the target keeps its collapsed flag. Nothing is selected or focused in
     * [to]. The host hears about it as for a drop: [TabBarHost.groupsChangedByDrag] for [from] (which loses the group; a split left
     * without tabs closes) and [TabBarHost.groupMovedIn] for [to]; both splits are refreshed afterwards. Returns false if nothing
     * moved (no host, unknown split or group). Used by Delete Group's *Merge Tabs* answer. EDT only.
     */
    fun moveGroup(from: SplitHandle, groupId: String, to: SplitHandle): Boolean = transfers.moveGroup(from, groupId, to)

    // ---- temporary splits ---------------------------------------------------------------------------------------

    /**
     * True if [split] is a temporary split: it holds only empty groups and is kept open by a hidden placeholder tab (never
     * reported as a file anywhere). False without a host.
     */
    fun isTemporary(split: SplitHandle): Boolean = host != null && temporary.isTemporary(split.window)

    /**
     * Closes the placeholder of every temporary split quietly, so that the editor layout the platform saves with the project
     * does not mention them (the platform stores every tab, and an in-memory file cannot be reopened). The groups are not asked about
     * or moved: this is for the project's close, after the host has captured its state. EDT.
     */
    fun closePlaceholders() {
        ThreadingAssertions.assertEventDispatchThread()
        if (host != null && !project.isDisposed) temporary.closeAll()
    }

    // ---- internal ---------------------------------------------------------------------------------------------------------

    /** The strip adapter of [split] (null without a host or for an unknown split). Tests and the sub-packages only. */
    internal fun adapterOf(split: SplitHandle): TabBarAdapter? = registry[split.window]?.adapter

    /** The editor windows of the project (every split, temporary ones included). */
    internal fun windows(): List<EditorWindow> = registry.windows()

    /** The split bars that exist now. */
    internal fun bars(): List<SplitBar> = registry.bars()

    /** The split bar of [window] while a host is attached (null otherwise): the overflow list reads its groups and reports picks. */
    internal fun splitBarOf(window: EditorWindow): SplitBar? = registry.splitBarOf(window)

    /** The drag layers of every split, for group drags that end in another split. */
    internal fun dragLayers(): List<TabBarDnd> = registry.dragLayers()

    /**
     * Brings the per-split state in line with the editor windows: repins hidden tabs, disposes what belongs to closed splits and
     * creates what new splits need. Called on every file open / close / selection change and once at start-up (see
     * [TabBarEditorListener]); new splits always open a file, closed ones are `EditorWindow.isDisposed`. No-op without a host.
     */
    internal fun sync() = registry.sync()

    /**
     * [file] was closed in some split. tells the host about every split whose applied groups list the file although it has no
     * tab there any more. The close of the tabs of a group that is being moved to another split is not reported for the source
     * split, because the source has been told about its new groups first and its index no longer lists the file.
     */
    internal fun fileClosed(file: VirtualFile) = registry.fileClosed(file)

    /**
     * [file] was pinned or unpinned somewhere (`FileEditorManagerListener.filePinStateChanged` names no window). tells the host
     * about every split in which the groups last applied list the file as a member and the file now has a pinned tab. No-op without a host.
     */
    internal fun filePinStateChanged(file: VirtualFile) = registry.filePinStateChanged(file)

    private fun detach() {
        if (!project.isDisposed && host != null) temporary.closeAll() // the placeholders are not left behind without a host
        previews.clear()
        registry.detach()
    }

    override fun dispose() {
        detach()
    }
}
