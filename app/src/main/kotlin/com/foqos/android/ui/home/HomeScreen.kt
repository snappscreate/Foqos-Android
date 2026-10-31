package com.foqos.android.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foqos.android.R
import com.foqos.android.BuildConfig
import com.foqos.android.data.model.StrategyPauseTimerData
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.domain.strategy.StrategyRegistry
import com.foqos.android.ui.common.formatScheduleSummary
import com.foqos.android.ui.common.strategyEmoji
import com.foqos.android.ui.insights.charts.HeatmapChart
import com.foqos.android.ui.insights.charts.MonthlyGridChart
import com.foqos.android.ui.insights.charts.WeeklyBarChart
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCreateProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onOpenInsights: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenActiveSession: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
                viewModel.refreshLocationState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    val context = LocalContext.current
    var deleteCandidate by remember { mutableStateOf<BlockedProfile?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings_action))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateProfile) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_create_profile_action))
            }
        },
        bottomBar = {
            AnimatedVisibility(visible = uiState.activeSession != null) {
                val session = uiState.activeSession
                val profile = uiState.activeProfile
                if (session != null && profile != null) {
                    ActiveSessionBar(
                        profileName = profile.name,
                        startTime = session.startTime,

                    )
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AnimatedVisibility(visible = uiState.permissionsMissing) {
                    AlertBanner(
                        text = stringResource(R.string.home_alert_permissions_missing),
                        onClick = onOpenSettings,
                    )
                }
            }
            item {
                AnimatedVisibility(visible = uiState.scheduleOutOfSync) {
                    AlertBanner(
                        text = stringResource(R.string.home_alert_schedule_out_of_sync),
                        onClick = {},
                    )
                }
            }

            if (BuildConfig.DEBUG && uiState.locationRule != null) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("開発用・位置範囲の擬似切替", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "現在の判定: " + when (uiState.locationInside) {
                                    true -> "範囲内"
                                    false -> "範囲外"
                                    null -> "未判定"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.simulateLocation(true) }) {
                                    Text("範囲に入る")
                                }
                                Button(onClick = { viewModel.simulateLocation(false) }) {
                                    Text("範囲から出る")
                                }
                            }
                            Text("次の実際の入退出イベントまで手動状態を維持", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                ActivityChartCard(
                    uiState = uiState,
                    onChartTypeSelected = viewModel::setChartType,
                )
            }

            if (uiState.profiles.isEmpty()) {
                item { EmptyProfilesState(onCreateProfile = onCreateProfile) }
            } else {
                items(uiState.profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isActive = uiState.activeSession?.profileId == profile.id,
                        activeSession = uiState.activeSession?.takeIf { it.profileId == profile.id },
                        isLocationProfile = uiState.locationRule?.profileId == profile.id,
                        locationInside = uiState.locationInside,
                        onCardClick = { onEditProfile(profile.id) },
                        onPlayStop = {
                            if (uiState.activeSession?.profileId == profile.id) {
                                if (uiState.locationRule?.profileId == profile.id) {
                                    viewModel.onLocationProfileAction(profile.id)
                                } else viewModel.stopActiveSession()
                            } else {
                                viewModel.startProfile(profile.id)
                            }
                        },
                        onEdit = { onEditProfile(profile.id) },
                        onInsights = { onOpenInsights(profile.id) },
                        onDuplicate = { viewModel.duplicateProfile(profile.id) },
                        onDeleteRequested = { deleteCandidate = profile },
                    )
                }
            }
        }
    }

    deleteCandidate?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.home_delete_profile_title)) },
            text = { Text(stringResource(R.string.home_delete_profile_message, profile.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProfile(profile.id)
                    deleteCandidate = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun AlertBanner(text: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityChartCard(uiState: HomeViewModel.UiState, onChartTypeSelected: (ChartType) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.home_activity_title), style = MaterialTheme.typography.titleMedium)
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_chart_type_action))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.chart_type_heatmap)) }, onClick = {
                            onChartTypeSelected(ChartType.HEATMAP); menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.chart_type_weekly)) }, onClick = {
                            onChartTypeSelected(ChartType.WEEKLY); menuExpanded = false
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.chart_type_monthly)) }, onClick = {
                            onChartTypeSelected(ChartType.MONTHLY); menuExpanded = false
                        })
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            when (uiState.chartType) {
                ChartType.HEATMAP -> HeatmapChart(days = uiState.heatmapDays)
                ChartType.WEEKLY -> uiState.weeklyAggregation?.let { WeeklyBarChart(aggregation = it) }
                ChartType.MONTHLY -> uiState.monthlyAggregation?.let { MonthlyGridChart(aggregation = it) }
            }
        }
    }
}

@Composable
private fun EmptyProfilesState(onCreateProfile: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "🎯", style = MaterialTheme.typography.displayMedium)
        Text(
            text = stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onCreateProfile) { Text(stringResource(R.string.home_empty_cta)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileCard(
    profile: BlockedProfile,
    isActive: Boolean,
    activeSession: com.foqos.android.data.model.BlockedProfileSession? = null,
    isLocationProfile: Boolean = false,
    locationInside: Boolean? = null,
    onCardClick: () -> Unit,
    onPlayStop: () -> Unit,
    onEdit: () -> Unit,
    onInsights: () -> Unit,
    onDuplicate: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val strategy = StrategyRegistry.byId(profile.strategyId)
    val blockedCount = profile.blockedPackages.size + profile.domains.size

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        onClick = onCardClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = strategyEmoji(strategy), style = MaterialTheme.typography.headlineSmall)

            Column(modifier = Modifier.weight(1f)) {
                Text(text = profile.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = strategy.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = pluralizeBlockedCount(blockedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (BuildConfig.DEBUG) {
                    Text(
                        "DEBUG: NFC登録 ${profile.physicalUnblockItems.count { it.type == PhysicalUnblockType.NFC }}件",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (isLocationProfile) {
                    Text(
                        text = "位置連動：" + when (locationInside) {
                            true -> "範囲内"
                            false -> "範囲外"
                            null -> "判定待ち"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (activeSession?.isPauseActive() == true) {
                    var nowMillis by remember(activeSession.id) { mutableStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(activeSession.id, activeSession.pauseStartTime) {
                        while (true) {
                            nowMillis = System.currentTimeMillis()
                            delay(1000L)
                        }
                    }
                    val pauseMinutes = if (profile.strategyId ==
                        com.foqos.android.domain.strategy.NfcPauseTimerBlockingStrategy.ID
                    ) StrategyPauseTimerData.fromJsonOrNull(profile.strategyDataJson).pauseDurationInMinutes
                    else profile.breakTimeInMinutes.coerceAtLeast(1)
                    val elapsedMs = nowMillis - (activeSession.pauseStartTime?.toEpochMilli() ?: nowMillis)
                    val leftSeconds = ((pauseMinutes * 60_000L - elapsedMs).coerceAtLeast(0L) + 999L) / 1000L
                    Text("一時停止中：残り ${leftSeconds / 60}分${leftSeconds % 60}秒",
                        style = MaterialTheme.typography.bodySmall)
                }
                profile.schedule?.takeIf { it.isConfigured }?.let { schedule ->
                    Text(
                        text = formatScheduleSummary(schedule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            IconButton(onClick = onPlayStop) {
                Icon(
                    imageVector = if (isActive && isLocationProfile && locationInside == true) Icons.Filled.Pause else if (isActive) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (isActive) stringResource(R.string.home_stop_action) else stringResource(R.string.home_start_action),
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_profile_menu_action))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_menu_insights)) }, onClick = { menuExpanded = false; onInsights() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.home_menu_duplicate)) }, onClick = { menuExpanded = false; onDuplicate() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menuExpanded = false; onDeleteRequested() })
                }
            }
        }
    }
}

private fun pluralizeBlockedCount(count: Int): String = if (count == 1) "1 app/site blocked" else "$count apps/sites blocked"

@Composable
private fun ActiveSessionBar(profileName: String, startTime: Instant) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(startTime) {
        while (true) {
            now = Instant.now()
            delay(1000)
        }
    }
    val elapsed = Duration.between(startTime, now).let { if (it.isNegative) Duration.ZERO else it }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = profileName, style = MaterialTheme.typography.titleSmall)
                Text(text = formatDuration(elapsed), style = MaterialTheme.typography.bodySmall)
            }
            Text("ブロック中", style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.seconds
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}
