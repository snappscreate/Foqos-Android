package com.foqos.android.ui.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.foqos.android.domain.insights.MonthlySessionAggregation
import kotlin.math.ceil

private const val COLUMNS = 7

/** Calendar-style grid of a month's daily focus-time intensity, mirroring iOS `MonthlySessionChart`. */
@Composable
fun MonthlyGridChart(aggregation: MonthlySessionAggregation, modifier: Modifier = Modifier) {
    val maxSeconds = aggregation.dailyDurations.maxOfOrNull { it.seconds }?.coerceAtLeast(1) ?: 1
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val fullColor = MaterialTheme.colorScheme.primary
    val rows = ceil(aggregation.daysInMonth / COLUMNS.toDouble()).toInt().coerceAtLeast(1)

    Canvas(modifier = modifier.fillMaxWidth().height((rows * 24).dp)) {
        val cellGap = 3.dp.toPx()
        val cellSize = (size.width - cellGap * (COLUMNS - 1)) / COLUMNS
        val cellHeight = (size.height - cellGap * (rows - 1)) / rows

        aggregation.dailyDurations.forEachIndexed { index, duration ->
            val column = index % COLUMNS
            val row = index / COLUMNS
            val fraction = (duration.seconds.toFloat() / maxSeconds.toFloat()).coerceIn(0f, 1f)
            val color = androidx.compose.ui.graphics.lerp(emptyColor, fullColor, if (duration.seconds > 0) 0.15f + fraction * 0.85f else 0f)

            drawRect(
                color = color,
                topLeft = Offset(column * (cellSize + cellGap), row * (cellHeight + cellGap)),
                size = Size(cellSize, cellHeight),
            )
        }
    }
}
