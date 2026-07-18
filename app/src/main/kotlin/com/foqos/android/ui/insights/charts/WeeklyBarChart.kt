package com.foqos.android.ui.insights.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.foqos.android.domain.insights.WeeklySessionAggregation

private val DAY_LABELS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/** Simple 7-bar chart of a week's daily focus time, mirroring iOS `WeeklySessionChart`. */
@Composable
fun WeeklyBarChart(aggregation: WeeklySessionAggregation, modifier: Modifier = Modifier) {
    val maxSeconds = aggregation.dailyDurations.maxOfOrNull { it.seconds }?.coerceAtLeast(1) ?: 1
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val barGap = 8.dp.toPx()
            val barWidth = (size.width - barGap * (7 - 1)) / 7
            aggregation.dailyDurations.forEachIndexed { index, duration ->
                val fraction = (duration.seconds.toFloat() / maxSeconds.toFloat()).coerceIn(0f, 1f)
                val barHeight = size.height * fraction
                val x = index * (barWidth + barGap)

                drawRect(color = trackColor, topLeft = Offset(x, 0f), size = Size(barWidth, size.height))
                drawRect(color = barColor, topLeft = Offset(x, size.height - barHeight), size = Size(barWidth, barHeight))
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            DAY_LABELS.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
