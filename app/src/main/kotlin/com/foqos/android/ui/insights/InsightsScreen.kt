@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.foqos.android.ui.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foqos.android.R
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.domain.session.SessionTimeCalculator
import com.foqos.android.ui.insights.charts.HeatmapChart
import com.foqos.android.ui.insights.charts.MonthlyGridChart
import com.foqos.android.ui.insights.charts.WeeklyBarChart
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun InsightsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var overflowExpanded by remember { mutableStateOf(false) }
    var showDeleteAllConfirm by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<BlockedProfileSession?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(uiState.profile?.name ?: stringResource(R.string.insights_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_cancel)) }
                },
                actions = {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.profile_edit_more_actions))
                    }
                    DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.insights_delete_all)) },
                            onClick = { overflowExpanded = false; showDeleteAllConfirm = true },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InsightsFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = uiState.filter == filter,
                            onClick = { viewModel.setFilter(filter) },
                            label = { Text(stringResource(filterLabelRes(filter)), maxLines = 1) },
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        when (uiState.filter) {
                            InsightsFilter.THIS_WEEK, InsightsFilter.LAST_WEEK ->
                                uiState.weeklyAggregation?.let { WeeklyBarChart(aggregation = it) }
                            InsightsFilter.THIS_MONTH, InsightsFilter.LAST_MONTH ->
                                uiState.monthlyAggregation?.let { MonthlyGridChart(aggregation = it) }
                            InsightsFilter.ALL -> HeatmapChart(days = uiState.heatmapDays)
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        TotalStat(stringResource(R.string.insights_total_focus), formatDuration(uiState.totalFocusTime))
                        TotalStat(stringResource(R.string.insights_total_break), formatDuration(uiState.totalBreakTime))
                        TotalStat(stringResource(R.string.insights_total_sessions), uiState.filteredSessions.size.toString())
                    }
                }
            }

            items(uiState.filteredSessions, key = { it.id }) { session ->
                SessionRow(session = session, onDelete = { deleteCandidate = session })
            }
        }
    }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text(stringResource(R.string.insights_delete_all_confirm_title)) },
            text = { Text(stringResource(R.string.insights_delete_all_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAllSessions(); showDeleteAllConfirm = false }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteAllConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    deleteCandidate?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.insights_delete_session_title)) },
            text = { Text(stringResource(R.string.insights_delete_session_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSession(session.id); deleteCandidate = null }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun TotalStat(label: String, value: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleLarge)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val ROW_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy - h:mm a")

@Composable
private fun SessionRow(session: BlockedProfileSession, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = session.startTime.atZone(ZoneId.systemDefault()).format(ROW_FORMATTER), style = MaterialTheme.typography.bodyMedium)
                val duration = session.duration()
                Text(
                    text = stringResource(R.string.insights_session_duration, formatDuration(duration)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete)) }
        }
    }
}

private fun filterLabelRes(filter: InsightsFilter): Int = when (filter) {
    InsightsFilter.THIS_WEEK -> R.string.insights_filter_this_week
    InsightsFilter.LAST_WEEK -> R.string.insights_filter_last_week
    InsightsFilter.THIS_MONTH -> R.string.insights_filter_this_month
    InsightsFilter.LAST_MONTH -> R.string.insights_filter_last_month
    InsightsFilter.ALL -> R.string.insights_filter_all
}

private fun formatDuration(duration: Duration): String {
    val safe = if (duration.isNegative) Duration.ZERO else duration
    val totalSeconds = safe.seconds
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
