package dev.willcodes.bettereditortabs.tabgroups.tabbar

import com.intellij.openapi.fileEditor.impl.EditorWindow

/**
 * Opaque runtime handle for one editor split (an internal [EditorWindow]).
 *
 * Code outside `tabbar/` passes these around but never sees the [EditorWindow] itself.
 * Equality is identity of the wrapped window, so a handle is only valid for the window's lifetime.
 * Stable identity across restarts is a separate concern (see the split-identity adapter).
 */
class SplitHandle internal constructor(internal val window: EditorWindow) {
    override fun equals(other: Any?): Boolean = other is SplitHandle && other.window === window
    override fun hashCode(): Int = System.identityHashCode(window)
    override fun toString(): String = "SplitHandle@${hashCode().toString(16)}"
}
