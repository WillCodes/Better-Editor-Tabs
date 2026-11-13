package dev.willcodes.bettereditortabs.tabgroups.model.strip

import java.awt.Dimension
import java.awt.Rectangle

/**
 * Layout of the floating picture of a group drag (INT-6): a bordered chip holding the group's pill and, after a small gap, the number
 * of tabs being moved. Pure arithmetic in layout pixels: the pill and the count are centred vertically in the chip, the chip is at
 * least [minHeight] tall (about a tab's picture) and wide enough for the border, the paddings, both pieces and the gap.
 */
object GroupChipLayout {
    /** The chip's [size] and where the pill and the count text go inside it (chip coordinates). */
    data class Result(val size: Dimension, val pill: Rectangle, val text: Rectangle)

    fun layout(
        pillWidth: Int,
        pillHeight: Int,
        textWidth: Int,
        textHeight: Int,
        minHeight: Int,
        paddingLeft: Int,
        paddingRight: Int,
        gap: Int,
        border: Int,
    ): Result {
        val width = 2 * border + paddingLeft + pillWidth + gap + textWidth + paddingRight
        val height = maxOf(minHeight, 2 * border + maxOf(pillHeight, textHeight))
        val inner = height - 2 * border
        val pillX = border + paddingLeft
        val textX = pillX + pillWidth + gap
        return Result(
            Dimension(width, height),
            Rectangle(pillX, border + (inner - pillHeight) / 2, pillWidth, pillHeight),
            Rectangle(textX, border + (inner - textHeight) / 2, textWidth, textHeight),
        )
    }
}
