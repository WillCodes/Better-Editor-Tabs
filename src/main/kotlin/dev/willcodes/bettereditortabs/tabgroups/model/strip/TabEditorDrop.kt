package dev.willcodes.bettereditortabs.tabgroups.model.strip

/** What a dragged tab becomes on release when the pointer is over an editor area that our drag handles itself ([TabEditorDrop.resolve]). */
enum class TabEditorLanding {
    /** Not ours: the platform's drag-out takes the drag (a split with visible tabs, outside every editor, the divider between splits). */
    PLATFORM,

    /** The edge of a bare split's editor area: a new split on that side with the tab in it. */
    NEW_SPLIT,

    /** The middle of the tab's own split, or any drop of the only tab of its split on its own editor: nothing moves, the tab comes back. */
    STAY,

    /** The middle of another bare split's editor area: the tab moves into that split (appended). */
    INTO_SPLIT,
}

/**
 * Pure choice of what a tab dragged off every strip does over an editor area, unit tested.
 *
 * Why it is ours: the platform's drag-out (`JBTabsImpl.processDropOver`) takes the drop side as CENTER whenever the split has no visible
 * tab (`visibleInfos.isEmpty()`), so it never offers an edge. In plain IntelliJ that is only the split whose only tab is the dragged
 * one; here a collapsed group (or a temporary split's placeholder) hides the other tabs as well, and the user cannot make a split. A
 * **bare** split (no visible tab but the dragged one) therefore gets the drop handled here, with the platform's targets: edge = new
 * split, middle = open in that split (`DockableEditorTabbedContainer.add`).
 */
object TabEditorDrop {
    /** [visible]: the files of the split's tabs that are shown (not hidden by a collapsed group, not a placeholder); [dragged]: the carried file. */
    fun <K> isBare(visible: Collection<K>, dragged: K): Boolean = visible.all { it == dragged }

    /**
     * [bare]: the split under the pointer is bare ([isBare]). [side]: the drop side of its editor area (null: the pointer is over none).
     * [ownSplit]: that split is the dragged tab's own. [onlyTab]: the dragged tab is its split's only tab at all (hidden ones count): a
     * new split would only replace the split with its twin, so the platform's own answer (nothing moves) is kept.
     */
    fun resolve(bare: Boolean, side: EditorSide?, ownSplit: Boolean, onlyTab: Boolean): TabEditorLanding = when {
        !bare || side == null -> TabEditorLanding.PLATFORM
        ownSplit && onlyTab -> TabEditorLanding.STAY
        side != EditorSide.CENTER -> TabEditorLanding.NEW_SPLIT
        ownSplit -> TabEditorLanding.STAY
        else -> TabEditorLanding.INTO_SPLIT
    }
}
