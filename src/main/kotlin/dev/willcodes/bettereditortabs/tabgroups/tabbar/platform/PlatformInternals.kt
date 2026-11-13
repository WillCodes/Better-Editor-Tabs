package dev.willcodes.bettereditortabs.tabgroups.tabbar.platform

import com.intellij.openapi.actionSystem.ActionStub
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.InternalUICustomization
import com.intellij.openapi.components.ComponentManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.fileEditor.impl.EditorsSplitters
import com.intellij.openapi.fileEditor.impl.FileEditorOpenOptions
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.docking.DockContainer
import com.intellij.ui.docking.DockManager
import com.intellij.ui.tabs.JBTabsBorder
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.DragHelper
import com.intellij.ui.tabs.impl.JBEditorTabs
import com.intellij.ui.tabs.impl.JBTabsImpl
import com.intellij.ui.tabs.impl.TabLayout
import com.intellij.ui.tabs.impl.multiRow.ScrollableMultiRowLayout
import com.intellij.ui.tabs.impl.singleRow.SingleRowLayout
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<PlatformInternals>()

/**
 * Every access to a platform member that is not reachable through public API, in one place: reflection (`getDeclaredField`,
 * `getMethod`, `getConstructor`), access by class-name string, and fail-soft calls to internal singletons.
 *
 * Rules: one documented accessor per platform member; each member is resolved once (lazily) and cached; every accessor fails
 * soft, i.e. when a member is missing (the plugin has no `untilBuild`, so a future platform may rename or remove it) the accessor
 * returns the fallback described in its KDoc and the feature that depended on it degrades quietly instead of failing.
 * [selfCheck] lists the members that could not be resolved; it runs once per IDE session ([logSelfCheckOnce]).
 *
 * The 262 source locations in the KDoc are from the platform sources of IntelliJ IDEA 2026.2.
 */
internal object PlatformInternals {
    private val selfCheckLogged = AtomicBoolean()

    private fun field(owner: Class<*>, name: String): Field? = try {
        owner.getDeclaredField(name).apply { isAccessible = true }
    }
    catch (e: Throwable) {
        null
    }

    // ---- JBTabsImpl.dragHelper / DragHelper.dragRec -----------------------------------------------------------------------

    /** `JBTabsImpl.dragHelper` (protected, `JBTabsImpl.kt:433`): the platform's in-strip tab drag. Missing: no platform drag is ever detected. */
    private val dragHelperField: Field? by lazy { field(JBTabsImpl::class.java, "dragHelper") }

    /** `DragHelper.dragRec` (package private, `DragHelper.java:42`): non-null while a tab is being dragged. Missing: as above. */
    private val dragRecField: Field? by lazy { field(DragHelper::class.java, "dragRec") }

    /**
     * True while the platform's own `DragHelper` is dragging a tab of [tabs] inside the strip (the condition `JBTabsImpl.moveDraggedTabLabel`
     * uses, `JBTabsImpl.kt:2281-2282`: `dragHelper.dragRec != null`). Fallback when a field is missing: false.
     */
    fun isTabDragActive(tabs: JBTabsImpl): Boolean {
        val helperField = dragHelperField ?: return false
        val recField = dragRecField ?: return false
        return try {
            val helper = helperField.get(tabs) ?: return false
            recField.get(helper) != null
        }
        catch (e: Throwable) {
            false
        }
    }

    /**
     * Tells the platform's `DragHelper` of [tabs] about a release it did not see (`MouseDragHelper.mousePressed` is final and records
     * the press point of every gesture that starts on a tab label; only its own `mouseReleased` forgets it again). With no drag in
     * progress it just forgets the press. Fallback when `dragHelper` is missing: nothing happens (a stale press could then start a
     * platform drag from a later drag event).
     */
    fun resetDragHelper(tabs: JBTabsImpl, release: MouseEvent) {
        (dragHelperField?.get(tabs) as? MouseListener)?.mouseReleased(release)
    }

    // ---- JBTabsImpl.tabBorder ---------------------------------------------------------------------------------------------

    /**
     * `JBTabsImpl.tabBorder` (private final, `JBTabsImpl.kt:429`): `drawBorder` reads this field and the class is not open, so the
     * only way to wrap the strip's border is to replace the field's value. Missing: [tabBorder] returns null and [setTabBorder] does
     * nothing; the only visible effect is the underline of a selected host tab showing under its pill.
     */
    private val tabBorderField: Field? by lazy { field(JBTabsImpl::class.java, "tabBorder") }

    fun tabBorder(tabs: JBTabsImpl): JBTabsBorder? = try {
        tabBorderField?.get(tabs) as? JBTabsBorder
    }
    catch (e: Throwable) {
        null
    }

    fun setTabBorder(tabs: JBTabsImpl, border: JBTabsBorder): Boolean {
        val f = tabBorderField ?: return false
        return try {
            f.set(tabs, border)
            true
        }
        catch (e: Throwable) {
            false
        }
    }

    // ---- JBTabsImpl.effectiveLayout / isMouseInsideTabsArea (scrolling the strip while dragging) -------------------------------

    /**
     * `JBTabsImpl.effectiveLayout` (`@JvmField internal var`, `JBTabsImpl.kt:376`): the strip's layout object, the only handle on its
     * scroll offset (`TabLayout.scroll` / `getScrollOffset` / `isScrollable` are public). Kotlin `internal` hides it from other modules,
     * but a `@JvmField` is a public JVM field (javap, 262 and 263). Missing: [tabLayout] returns null and the strip is not scrolled.
     */
    private val effectiveLayoutField: Field? by lazy { field(JBTabsImpl::class.java, "effectiveLayout") }

    /**
     * `JBTabsImpl.isMouseInsideTabsArea` (private field behind a property with a private setter, `JBTabsImpl.kt:459`). The platform
     * scrolls the strip back to the selected tab on every layout pass unless this is true (`ScrollableSingleRowLayout.doScrollToSelectedTab`,
     * `ScrollableMultiRowLayout.layoutTable`) and keeps it up to date from an AWT mouse-motion listener (`JBTabsImpl.kt:643-664`).
     * During our tab drag that listener sees nothing: the drag events are consumed by a glass-pane preprocessor, and a consumed event
     * is not dispatched to AWT at all (`IdeGlassPaneImpl.dispatchMouseEvent` returns true, `IdeEventQueue.dispatchByCustomDispatchers`
     * returns before `super.dispatchEvent`); and pressing the tab selected it, which sets the flag to false (`doSetSelected`,
     * `JBTabsImpl.kt:1579`). So while we drag, the flag is false and every layout pass undoes a scroll of the strip. Missing: [mouseInsideTabsArea]
     * returns null and [setMouseInsideTabsArea] does nothing (the strip scrolls back to the dragged tab after each tick).
     */
    private val mouseInsideField: Field? by lazy { field(JBTabsImpl::class.java, "isMouseInsideTabsArea") }

    /** The layout object of [tabs] (see [effectiveLayoutField]), or null if it cannot be read. */
    fun tabLayout(tabs: JBTabsImpl): TabLayout? = try {
        effectiveLayoutField?.get(tabs) as? TabLayout
    }
    catch (e: Throwable) {
        null
    }

    /**
     * Does [layout] limit its own scroll offset to the content (`ScrollableSingleRowLayout.scroll` clamps; `ScrollableMultiRowLayout.scroll`
     * just adds)? Decided by the base class, `SingleRowLayout` (public, `@ApiStatus.Internal`).
     */
    fun layoutClampsOffset(layout: TabLayout): Boolean = layout is SingleRowLayout

    /**
     * Does the strip's layout cut the label at the end of the visible area (so that a label's bounds are shorter than its layout length)?
     * The single-row layouts do: `ScrollableSingleRowLayout.applyTabLayout` lays the label that does not fit out with the visible part
     * as its bounds (`SingleRowLayoutStrategy.drawPartialOverflowTabs`). So does the unpinned row of the separate-pinned-row layout,
     * `ScrollableMultiRowLayout` (its `ScrollableTabsRow.layoutTabs` gives the last label `x + tabsLength - curX` instead of its preferred
     * width; user report 2026-11-13: a collapsed group's trailing pill moved onto the cut tab). Its pinned row (`CompressibleTabsRow`) never
     * hosts pills in that mode. The wrapping and squeezing layouts lay out whole labels. Both classes are public (`@ApiStatus.Internal`);
     * false if the layout cannot be read.
     */
    fun cutsLabelsAtEnd(tabs: JBTabsImpl): Boolean = tabLayout(tabs).let { it is SingleRowLayout || it is ScrollableMultiRowLayout }

    /** The platform's "mouse is inside the tab area" flag of [tabs] (see [mouseInsideField]), or null if it cannot be read. */
    fun mouseInsideTabsArea(tabs: JBTabsImpl): Boolean? = try {
        mouseInsideField?.getBoolean(tabs)
    }
    catch (e: Throwable) {
        null
    }

    /** Sets the platform's "mouse is inside the tab area" flag of [tabs]; false if it cannot be written. */
    fun setMouseInsideTabsArea(tabs: JBTabsImpl, value: Boolean): Boolean {
        val f = mouseInsideField ?: return false
        return try {
            f.setBoolean(tabs, value)
            true
        }
        catch (e: Throwable) {
            false
        }
    }

    // ---- EditorWindow.getTabToSelect ----------------------------------------------------------------------------------------

    /**
     * `EditorWindow.getTabToSelect(tabBeingClosed, fileBeingClosed, componentIndex)` (Kotlin `internal`, `EditorWindow.kt:766`): the tab
     * the editor area selects when a tab leaves its strip, which `EditorTabbedContainerDragOutDelegate.dragOutStarted` asks before it hides
     * the dragged tab: the tab selected before it ("previous selection", if it is still showing), else the one the close settings pick
     * (the recently used editor, the one to the right, or the left neighbour, `computeIndexToSelect`). A Kotlin `internal` member is
     * compiled to the mangled name `getTabToSelect$intellij_platform_ide_impl` (the same in 262 and 263, javap). Missing: [tabToSelectOnDragOut]
     * returns [TabToSelect.Unknown] and the caller falls back to a neighbour.
     */
    private val tabToSelectMethod: java.lang.reflect.Method? by lazy {
        try {
            EditorWindow::class.java.getDeclaredMethod(
                "getTabToSelect\$intellij_platform_ide_impl", TabInfo::class.java, VirtualFile::class.java, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }
        catch (e: Throwable) {
            null
        }
    }

    /** The answer of [tabToSelectOnDragOut]: the platform's choice (which can be "none"), or no answer. */
    sealed class TabToSelect {
        data class Tab(val info: TabInfo?) : TabToSelect()
        object Unknown : TabToSelect()
    }

    /** The tab [window] selects when [info] (the tab of [file], at [index] in the strip's tab list) leaves its strip, see [tabToSelectMethod]. */
    fun tabToSelectOnDragOut(window: EditorWindow, info: TabInfo, file: VirtualFile, index: Int): TabToSelect {
        val method = tabToSelectMethod ?: return TabToSelect.Unknown
        return try {
            TabToSelect.Tab(method.invoke(window, info, file, index) as? TabInfo)
        }
        catch (e: Throwable) {
            TabToSelect.Unknown
        }
    }

    // ---- EditorWindow.removedTabs -----------------------------------------------------------------------------------------

    /**
     * `EditorWindow.removedTabs` (private, `EditorWindow.kt:142`, an `ArrayDeque<RemovedTabInfo>`): the history behind Reopen Closed Tab.
     * `closeFile` always appends to it and has no flag to skip that. Missing (or of an unexpected type): a warning is logged once and
     * [removedTabs] returns null, so a moved tab stays in the source split's Reopen Closed Tab history (cosmetic).
     */
    private val removedTabsField: Field? by lazy {
        try {
            val f = EditorWindow::class.java.getDeclaredField("removedTabs")
            f.isAccessible = true
            if (!MutableList::class.java.isAssignableFrom(f.type)) error("unexpected type ${f.type}")
            f
        }
        catch (e: Throwable) {
            LOG.warn("EditorWindow.removedTabs not accessible; moved tabs will stay in Reopen Closed Tab history", e)
            null
        }
    }

    /** The Reopen Closed Tab history of [window], oldest first, or null if the field can't be read. The list is live. */
    @Suppress("UNCHECKED_CAST")
    fun removedTabs(window: EditorWindow): MutableList<Any>? = try {
        removedTabsField?.get(window) as? MutableList<Any>
    }
    catch (e: Throwable) {
        null
    }

    private var removedTabFileField: Field? = null

    /** `RemovedTabInfo.file` (private, `EditorWindow.kt:1347`): the file of one history entry. Missing: null. */
    fun removedTabFile(entry: Any): VirtualFile? = try {
        val f = removedTabFileField ?: entry.javaClass.getDeclaredField("file").also {
            it.isAccessible = true
            removedTabFileField = it
        }
        f.get(entry) as? VirtualFile
    }
    catch (e: Throwable) {
        null
    }

    // ---- DockManagerImpl.containerToWindow / DockWindow.getId -------------------------------------------------------------

    /**
     * The persisted id of the `DockWindow` hosting [owner] (the `id` attribute in the `DockManager` workspace state), which stays the
     * same across restarts for restored windows. It is only reachable through the private map `DockManagerImpl.containerToWindow`
     * (`DockManagerImpl.kt:82`) and the `getId()` getter of the internal class `DockWindow` (`DockWindow.kt:46`). Missing: a warning is
     * logged and null is returned; callers then fall back to matching splits by their open files.
     */
    fun dockWindowId(owner: EditorsSplitters): String? {
        try {
            val dockManager = DockManager.getInstance(owner.manager.project)
            val containerToWindow = dockManager.javaClass.getDeclaredField("containerToWindow").apply { isAccessible = true }
            val map = containerToWindow.get(dockManager) as? Map<*, *> ?: return null
            for ((container, window) in map) {
                if (container is DockContainer && container.containerComponent === owner && window != null) {
                    return window.javaClass.getMethod("getId").invoke(window) as? String
                }
            }
        }
        catch (t: Throwable) {
            LOG.warn("Cannot read the dock window id by reflection", t)
        }
        return null
    }

    // ---- FileEditorOpenOptions ----------------------------------------------------------------------------------------------

    /**
     * `FileEditorOpenOptions` is a Kotlin data class whose primary constructor changes between releases (263 added a parameter), and a
     * Kotlin call such as `FileEditorOpenOptions(selectAsCurrent = false)` compiles to its synthetic default-argument constructor, so
     * it breaks with NoSuchMethodError on the next release. The no-arg constructor and the `with*(Boolean)` copy methods exist in
     * both 262 and 263 (checked with javap), but Kotlin cannot call the no-arg constructor directly, hence this reflective lookup.
     * Missing: moving a group between splits fails with an exception (there is no sensible fallback).
     */
    private val baseOpenOptions: FileEditorOpenOptions by lazy { FileEditorOpenOptions::class.java.getConstructor().newInstance() }

    /** Open options for moving a file into another split without selecting or focusing it. */
    fun backgroundOpenOptions(): FileEditorOpenOptions = baseOpenOptions.withSelectAsCurrent(false).withRequestFocus(false)

    // ---- Islands painter ------------------------------------------------------------------------------------------------------

    private const val ISLANDS_PAINTER = "IslandsTabPainter"
    private const val ISLANDS_PAINTER_CLASS = "com.intellij.openapi.application.impl.islands.IslandsTabPainter"

    /**
     * Is [painter] the Islands tab painter (`IslandsTabPainter`, an internal class, `IslandsTabPainter.kt:120`)? Islands paints a tab
     * as a rounded rectangle inset from its label, and handles the tab colour's alpha differently. Matched by simple class name;
     * missing/renamed class: false, i.e. the classic New UI rendering is assumed.
     */
    fun isIslandsPainter(painter: Any): Boolean = painter.javaClass.simpleName == ISLANDS_PAINTER

    /**
     * Does the platform draw tabs rounded while they are dragged (Islands)? What `DockManagerImpl` and `TabLabel.paint` ask
     * (`InternalUICustomization.isRoundedTabDuringDrag`, `DockManagerImpl.kt:354`, `IslandsUICustomization.kt:203`). Internal API, so any
     * failure means "not rounded".
     */
    fun isRoundedTabDuringDrag(): Boolean = try {
        InternalUICustomization.getInstance()?.isRoundedTabDuringDrag == true
    }
    catch (e: Throwable) {
        false
    }

    // ---- JBEditorTabs.isAlphabeticalModeChanged (IDE-2) -------------------------------------------------------------------------

    /**
     * `JBEditorTabs.isAlphabeticalModeChanged` (private boolean, `JBEditorTabs.kt:23`). `JBEditorTabs.isAlphabeticalMode()` follows
     * `UISettings.sortTabsAlphabetically` only until `setAlphabeticalMode(..)` is called once on the strip; this flag is then set for
     * good (`JBEditorTabs.kt:71-77`) and the strip ignores the setting. IDE-2 with groups switches the platform's view sorting off for
     * a strip whose order the plugin sorts itself (`setAlphabeticalMode(false)`), and clears the flag again to give the strip back to
     * the platform. Missing: [followUiSettings] returns false and the caller sets the mode by hand (`setAlphabeticalMode(UISettings...)`),
     * which then has to be repeated whenever the setting changes.
     */
    private val alphabeticalChangedField: Field? by lazy {
        field(JBEditorTabs::class.java, "isAlphabeticalModeChanged")?.takeIf { it.type == Boolean::class.javaPrimitiveType }
    }

    /** Makes [tabs] follow `UISettings.sortTabsAlphabetically` again (see [alphabeticalChangedField]); false if that is not possible. */
    fun followUiSettings(tabs: JBEditorTabs): Boolean {
        val f = alphabeticalChangedField ?: return false
        return try {
            f.setBoolean(tabs, false)
            true
        }
        catch (e: Throwable) {
            false
        }
    }

    // ---- action stubs ---------------------------------------------------------------------------------------------------------

    /**
     * The real action behind [action] when it is an `ActionStub` (an action registered in XML whose class has not been loaded yet),
     * else [action] itself. A stub's `actionPerformed` throws `UnsupportedOperationException`, and `ActionManagerImpl.getBaseAction`
     * hands back the replaced action as it was registered, i.e. usually still a stub: the platform only converts stubs it looks up
     * by id (`getAction`), and the id of an overridden action now resolves to the override. Converted the way the platform does it
     * (`convertActionStub`, `ActionManagerXmlSupport.kt:348`): `ComponentManager.instantiateClass(className, plugin)` (internal) from
     * the application, then `ActionStub.initAction` (internal) copies the template presentation and shortcuts. The caller caches
     * the result. Any failure: null (the caller then has no base action and stays disabled rather than throwing).
     */
    fun unstub(action: AnAction): AnAction? {
        if (action !is ActionStub) return action
        return try {
            val real = ApplicationManager.getApplication().instantiateClass<AnAction>(action.className, action.plugin)
            action.initAction(real)
            real
        }
        catch (e: Throwable) {
            LOG.warn("Could not load action ${action.id} (${action.className})", e)
            null
        }
    }

    // ---- self check -----------------------------------------------------------------------------------------------------------

    /** The platform members this object reaches by reflection or by name that cannot be resolved in the running IDE; empty = all there. */
    fun selfCheck(): List<String> {
        val missing = ArrayList<String>()
        fun check(name: String, ok: () -> Boolean) {
            val resolved = try {
                ok()
            }
            catch (e: Throwable) {
                false
            }
            if (!resolved) missing += name
        }
        check("JBTabsImpl.dragHelper") { dragHelperField != null }
        check("DragHelper.dragRec") { dragRecField != null }
        check("JBTabsImpl.tabBorder") { tabBorderField != null }
        check("JBTabsImpl.effectiveLayout") { effectiveLayoutField != null }
        check("JBTabsImpl.isMouseInsideTabsArea") { mouseInsideField?.type == Boolean::class.javaPrimitiveType }
        check("JBEditorTabs.isAlphabeticalModeChanged") { alphabeticalChangedField != null }
        check("EditorWindow.getTabToSelect") { tabToSelectMethod != null }
        check("EditorWindow.removedTabs") { removedTabsField != null }
        check("RemovedTabInfo.file") { Class.forName("com.intellij.openapi.fileEditor.impl.RemovedTabInfo").getDeclaredField("file") != null }
        check("DockManagerImpl.containerToWindow") {
            Class.forName("com.intellij.ui.docking.impl.DockManagerImpl").getDeclaredField("containerToWindow") != null
        }
        check("DockWindow.getId()") { Class.forName("com.intellij.ui.docking.impl.DockWindow").getMethod("getId") != null }
        check("FileEditorOpenOptions.<init>()") { FileEditorOpenOptions::class.java.getConstructor() != null }
        check("IslandsTabPainter") { Class.forName(ISLANDS_PAINTER_CLASS).simpleName == ISLANDS_PAINTER }
        check("ComponentManager.instantiateClass(String, PluginDescriptor)") {
            ComponentManager::class.java.getMethod("instantiateClass", String::class.java, PluginDescriptor::class.java) != null
        }
        check("ActionStub.initAction") { ActionStub::class.java.getMethod("initAction", AnAction::class.java) != null }
        return missing
    }

    /** Runs [selfCheck] the first time it is called in this IDE session and logs the result: INFO if all resolved, WARN naming the missing ones. */
    fun logSelfCheckOnce() {
        if (!selfCheckLogged.compareAndSet(false, true)) return
        val missing = selfCheck()
        if (missing.isEmpty()) LOG.info("Platform self-check: all internal hooks resolved")
        else LOG.warn("Platform self-check: ${missing.size} internal hook(s) could not be resolved, the dependent features are degraded: ${missing.joinToString()}")
    }
}
