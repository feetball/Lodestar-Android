package net.kuecker.lodestar.features.aim

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp

/**
 * Lays its children out in rows that wrap when the width runs out, each row centred. Children get their natural size
 * (up to the available width); the layout is as wide as its widest row, or as the constraints demand (use
 * `fillMaxWidth()` to centre the rows in the full width). A row may reach [bleed] beyond each side of the available
 * width, into the margin around the layout, before the next child starts a new row.
 */
@Composable
internal fun AimFlowLayout(
    modifier: Modifier = Modifier,
    spacing: Dp = 6.dp,
    lineSpacing: Dp = 6.dp,
    bleed: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val spacingPx = spacing.roundToPx()
        val lineSpacingPx = lineSpacing.roundToPx()
        val rowLimit =
            if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * bleed.roundToPx() else constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        val rows = rows(placeables, rowLimit, spacingPx)
        val rowWidths = rows.map { row -> rowWidth(row, spacingPx) }
        val rowHeights = rows.map { row -> row.maxOfOrNull { it.height } ?: 0 }
        val width = constraints.constrainWidth(rowWidths.maxOrNull() ?: 0)
        val height = constraints.constrainHeight(rowHeights.sum() + lineSpacingPx * maxOf(rows.size - 1, 0))
        layout(width, height) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = (width - rowWidths[rowIndex]) / 2
                for (placeable in row) {
                    placeable.placeRelative(x, y + (rowHeights[rowIndex] - placeable.height) / 2)
                    x += placeable.width + spacingPx
                }
                y += rowHeights[rowIndex] + lineSpacingPx
            }
        }
    }
}

/** The children of each row: a child that does not fit beside the ones before it starts the next row. */
private fun rows(placeables: List<Placeable>, maxWidth: Int, spacing: Int): List<List<Placeable>> {
    val rows = mutableListOf<List<Placeable>>()
    var current = mutableListOf<Placeable>()
    var currentWidth = 0
    for (placeable in placeables) {
        val widthWithChild = if (current.isEmpty()) placeable.width else currentWidth + spacing + placeable.width
        if (current.isNotEmpty() && widthWithChild > maxWidth) {
            rows.add(current)
            current = mutableListOf(placeable)
            currentWidth = placeable.width
        } else {
            current.add(placeable)
            currentWidth = widthWithChild
        }
    }
    if (current.isNotEmpty()) rows.add(current)
    return rows
}

private fun rowWidth(row: List<Placeable>, spacing: Int): Int =
    row.sumOf { it.width } + spacing * maxOf(row.size - 1, 0)
