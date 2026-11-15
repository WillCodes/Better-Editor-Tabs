package dev.willcodes.bettereditortabs.tabgroups.ui.popup

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.Icon
import javax.swing.JComponent

/**
 * A clickable row that looks like an item of a New UI list popup: [JBUI.CurrentTheme.List.rowHeight] high, the text indented by the
 * selection inset plus the inner inset, and a rounded selection background (`Popup.Selection.arc`, inset by
 * `Popup.Selection.leftRightInset`) while the mouse is over the row or it has the keyboard focus. A disabled row paints the disabled
 * foreground, has no highlight and is not focusable.
 *
 * A hovered row is highlighted; when the keyboard focus is on another row it moves to the hovered one, so at most one row is
 * highlighted at a time, like the selection of a list popup (the name field keeps its focus while the mouse passes over the rows).
 * Enter and Space (and a click) call [onActivate]. [trailingIcon], when set, is painted right after the text (the disclosure chevron
 * of "Advanced ▸"), so every row's text starts at the same x.
 */
internal class PopupMenuRow(
    text: String,
    enabled: Boolean,
    /** A destructive action (Delete Group, user request 2026-11-14): its text is bold and in the error colour. */
    private val danger: Boolean = false,
    private val onActivate: () -> Unit,
) : JComponent() {
    var text: String = text
        set(value) {
            field = value
            getAccessibleContext().accessibleName = value
            revalidate()
            repaint()
        }

    var trailingIcon: Icon? = null
        set(value) {
            field = value
            revalidate()
            repaint()
        }

    private var hovered = false

    init {
        isOpaque = false
        font = if (danger) JBUI.Fonts.label().asBold() else JBUI.Fonts.label()
        isEnabled = enabled
        isFocusable = enabled
        // getAccessibleContext(), not `accessibleContext`: in a JComponent subclass Kotlin resolves that name to Swing's protected
        // field, which is null until the getter creates the context.
        getAccessibleContext().accessibleName = text
        if (enabled) {
            val mouse = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) = setHovered(true)
                override fun mouseExited(e: MouseEvent) = setHovered(false)
                override fun mouseMoved(e: MouseEvent) {
                    // Take the focus over from another row only, so that one row is highlighted at a time; never from the name
                    // field or the swatches (passing the mouse over the rows must not end typing).
                    if (!isFocusOwner && KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner is PopupMenuRow) {
                        requestFocusInWindow()
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && contains(e.point)) onActivate()
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) = repaint()
                override fun focusLost(e: FocusEvent) = repaint()
            })
            bindKey("ENTER", onActivate)
            bindKey("SPACE", onActivate)
        }
    }

    private fun setHovered(value: Boolean) {
        hovered = value
        repaint()
    }

    private fun textLeft(): Int = JBUI.CurrentTheme.Popup.Selection.LEFT_RIGHT_INSET.get() + JBUI.CurrentTheme.Popup.Selection.innerInsets().left

    private fun gap(): Int = JBUI.CurrentTheme.ActionsList.elementIconGap()

    override fun getPreferredSize(): Dimension {
        val icon = trailingIcon
        val iconWidth = if (icon != null) icon.iconWidth + gap() else 0
        val width = textLeft() * 2 + iconWidth + getFontMetrics(font).stringWidth(text)
        return Dimension(width, JBUI.CurrentTheme.List.rowHeight())
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            val highlighted = isEnabled && (hovered || isFocusOwner)
            if (highlighted) {
                GraphicsUtil.setupAAPainting(g2)
                val inset = JBUI.CurrentTheme.Popup.Selection.LEFT_RIGHT_INSET.get()
                val arc = JBUI.CurrentTheme.Popup.Selection.ARC.get()
                g2.color = JBUI.CurrentTheme.List.background(true, true)
                g2.fillRoundRect(inset, 0, width - 2 * inset, height, arc, arc)
            }
            g2.color = when {
                !isEnabled -> UIUtil.getLabelDisabledForeground()
                danger -> com.intellij.util.ui.NamedColorUtil.getErrorForeground()
                else -> JBUI.CurrentTheme.List.foreground(highlighted, true)
            }
            val x = textLeft()
            UISettings.setupAntialiasing(g2)
            g2.font = font
            val fm = g2.fontMetrics
            g2.drawString(text, x, (height - fm.height) / 2 + fm.ascent)
            trailingIcon?.let {
                val painted = if (isEnabled) it else IconLoader.getDisabledIcon(it)
                painted.paintIcon(this, g2, x + fm.stringWidth(text) + gap(), (height - painted.iconHeight) / 2)
            }
        } finally {
            g2.dispose()
        }
    }

    override fun getAccessibleContext(): AccessibleContext {
        if (accessibleContext == null) {
            accessibleContext = object : AccessibleJComponent() {
                override fun getAccessibleRole(): AccessibleRole = AccessibleRole.PUSH_BUTTON
            }
        }
        return accessibleContext
    }
}
