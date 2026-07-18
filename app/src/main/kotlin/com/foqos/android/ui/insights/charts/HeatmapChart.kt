package com.foqos.android.ui.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.dp
import com.foqos.android.domain.insights.HeatmapDay
import java.time.Duration

private const val COLUMNS = 7
private const val ROWS = 4

/**
 * Renders the last 28 days of focus time as a GitHub-style intensity heatmap: [HeatmapAggregator]
 * already returns [days] oldest-first, so this simply lays them out column-major (each column is
 * one week, top-to-bottom = day within that week).
 */
@Composable
fun HeatmapChart(days: List<HeatmapDay>, modifier: Modifier = Modifier) {
    val maxSeconds = days.maxOfOrNull { it.totalFocusTime.seconds }?.coerceAtLeast(1) ?: 1
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val fullColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth().height(140.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            if (days.isEmpty()) return@Canvas
            val cellGap = 4.dp.toPx()
            val cellSize = (size.width - cellGap * (COLUMNS - 1)) / COLUMNS
            val cellHeight = (size.height - cellGap * (ROWS - 1)) / ROWS

            days.forEachIndexed { index, day ->
                val column = index / ROWS
                val row = index % ROWS
                val intensity = (day.totalFocusTime.seconds.toFloat() / maxSeconds.toFloat()).coerceIn(0f, 1f)
                val color = androidx.compose.ui.graphics.lerp(
                    emptyColor,
                    fullColor,
                    if (day.totalFocusTime > Duration.ZERO) 0.15f + intensity * 0.85f else 0f,
                )

                drawRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(column * (cellSize + cellGap), row * (cellHeight + cellGap)),
                    size = Size(cellSize, cellHeight),
                    style = Fill,
                )
            }
        }
    }
}
