@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.foqos.android.ui.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foqos.android.R
import com.foqos.android.data.model.BlockedProfileSchedule
import com.foqos.android.data.model.SoftUnblockStrategyData
import com.foqos.android.data.model.StrategyPauseTimerData
import com.foqos.android.data.model.StrategyTimerData
import com.foqos.android.domain.strategy.StrategyRegistry
import com.foqos.android.service.nfc.NfcTagWriter
import com.foqos.android.service.qr.QrCodeGenerator
import com.foqos.android.ui.common.FoqosTimePickerDialog
import com.foqos.android.ui.common.WEEKDAY_DISPLAY_ORDER
import com.foqos.android.ui.common.formatScheduleSummary
import com.foqos.android.ui.common.shortDayName
import com.foqos.android.ui.common.strategyEmoji
import com.foqos.android.ui.physicalunlock.PhysicalUnlockEditor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ProfileEditScreen(
    viewModel: ProfileEditViewModel,
    onDone: () -> Unit,
    onOpenAppPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onDone()
    }
    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onDone()
    }

    if (uiState.loading) return

    var showStrategyPicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAddDomainDialog by remember { mutableStateOf(false) }
    var showWriteNfcSheet by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isNewProfile) stringResource(R.string.profile_edit_title_new) else stringResource(R.string.profile_edit_title_edit)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::save) { Text(stringResource(R.string.action_save)) }
                    if (uiState.isPersisted) {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.profile_edit_more_actions))
                        }
                        DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profile_edit_write_nfc_tag)) },
                                leadingIcon = { Icon(Icons.Filled.Nfc, contentDescription = null) },
                                onClick = { overflowExpanded = false; showWriteNfcSheet = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profile_edit_show_qr_code)) },
                                leadingIcon = { Icon(Icons.Filled.QrCode, contentDescription = null) },
                                onClick = { overflowExpanded = false; showQrDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                onClick = { overflowExpanded = false; showDeleteConfirm = true },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            NameSection(name = uiState.draft.name, error = uiState.nameError, onNameChange = viewModel::updateName)

            StrategySection(
                strategyId = uiState.draft.strategyId,
                strategyDataJson = uiState.draft.strategyDataJson,
                onOpenPicker = { showStrategyPicker = true },
                onStrategyDataChange = viewModel::updateStrategyDataJson,
            )

            AppsSection(blockedCount = uiState.draft.blockedPackages.size, onOpenAppPicker = onOpenAppPicker)

            WebsitesSection(
                domains = uiState.draft.domains,
                allowModeDomains = uiState.draft.allowModeDomains,
                onAddClick = { showAddDomainDialog = true },
                onRemove = viewModel::removeDomain,
                onAllowModeChange = viewModel::setAllowModeDomains,
            )

            if (uiState.isPersisted) {
                SectionCard(title = stringResource(R.string.profile_edit_physical_unlocks_title)) {
                    PhysicalUnlockEditor(
                        profileId = uiState.draft.id,
                        profileRepository = viewModel.profileRepository,
                        nfcDispatchManager = viewModel.nfcDispatchManager,
                    )
                }
            } else {
                SectionCard(title = stringResource(R.string.profile_edit_physical_unlocks_title)) {
                    Text(
                        text = stringResource(R.string.profile_edit_physical_unlocks_save_first),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            ScheduleSection(
                enabled = uiState.scheduleEnabled,
                schedule = uiState.draft.schedule,
                onEnabledChange = viewModel::setScheduleEnabled,
                onToggleDay = viewModel::toggleScheduleDay,
                onStartClick = { showStartTimePicker = true },
                onEndClick = { showEndTimePicker = true },
            )

            BreaksSection(
                enabled = uiState.draft.enableBreaks,
                durationMinutes = uiState.draft.breakTimeInMinutes,
                allowMultiple = uiState.draft.allowMultipleBreaks,
                onEnabledChange = viewModel::setEnableBreaks,
                onDurationChange = viewModel::setBreakDurationMinutes,
                onAllowMultipleChange = viewModel::setAllowMultipleBreaks,
            )

            SessionProtectionSection(
                strictMode = uiState.draft.enableStrictMode,
                blockAppInstallation = uiState.draft.enableBlockAppInstallation,
                disableBackgroundStops = uiState.draft.disableBackgroundStops,
                enableEmergencyUnblock = uiState.draft.enableEmergencyUnblock,
                onStrictModeChange = viewModel::setEnableStrictMode,
                onBlockAppInstallationChange = viewModel::setEnableBlockAppInstallation,
                onDisableBackgroundStopsChange = viewModel::setDisableBackgroundStops,
                onEnableEmergencyUnblockChange = viewModel::setEnableEmergencyUnblock,
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showStrategyPicker) {
        com.foqos.android.ui.profile.StrategyPickerSheet(
            categorized = viewModel.strategyCategories(),
            selectedStrategyId = uiState.draft.strategyId,
            onSelect = { strategy -> viewModel.selectStrategy(strategy); showStrategyPicker = false },
            onDismiss = { showStrategyPicker = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.home_delete_profile_title)) },
            text = { Text(stringResource(R.string.home_delete_profile_message, uiState.draft.name)) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; viewModel.delete() }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showAddDomainDialog) {
        AddDomainDialog(
            error = uiState.domainError,
            onConfirm = { value -> viewModel.addDomain(value) },
            onDismiss = { showAddDomainDialog = false; viewModel.clearDomainError() },
            dismissOnSuccess = uiState.domainError == null,
        )
    }

    if (showStartTimePicker) {
        val schedule = uiState.draft.schedule
        FoqosTimePickerDialog(
            initialHour = schedule?.startHour ?: 9,
            initialMinute = schedule?.startMinute ?: 0,
            onConfirm = { h, m -> viewModel.setScheduleStartTime(h, m); showStartTimePicker = false },
            onDismiss = { showStartTimePicker = false },
        )
    }
    if (showEndTimePicker) {
        val schedule = uiState.draft.schedule
        FoqosTimePickerDialog(
            initialHour = schedule?.endHour ?: 17,
            initialMinute = schedule?.endMinute ?: 0,
            onConfirm = { h, m -> viewModel.setScheduleEndTime(h, m); showEndTimePicker = false },
            onDismiss = { showEndTimePicker = false },
        )
    }

    if (showWriteNfcSheet) {
        WriteNfcTagDialog(
            viewModel = viewModel,
            hasPhysicalUnlocks = uiState.draft.physicalUnblockItems.isNotEmpty(),
            profileName = uiState.draft.name,
            onDismiss = { showWriteNfcSheet = false },
        )
    }

    if (showQrDialog) {
        ShowQrCodeDialog(profileId = uiState.draft.id, profileName = uiState.draft.name, onDismiss = { showQrDialog = false })
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp))
            content()
        }
    }
}

@Composable
private fun NameSection(name: String, error: String?, onNameChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.profile_edit_name_label)) },
        isError = error != null,
        supportingText = { if (error != null) Text(error) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StrategySection(
    strategyId: String?,
    strategyDataJson: String?,
    onOpenPicker: () -> Unit,
    onStrategyDataChange: (String) -> Unit,
) {
    val strategy = StrategyRegistry.byId(strategyId)
    SectionCard(title = stringResource(R.string.profile_edit_strategy_title)) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            onClick = onOpenPicker,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = strategyEmoji(strategy), style = MaterialTheme.typography.headlineSmall)
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = strategy.name, style = MaterialTheme.typography.titleSmall)
                    Text(text = strategy.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (strategy.hasTimer) {
            val timerData = StrategyTimerData.fromJsonOrNull(strategyDataJson)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(text = stringResource(R.string.profile_edit_timer_duration_label, timerData.durationInMinutes))
                Slider(
                    value = timerData.durationInMinutes.toFloat(),
                    onValueChange = { onStrategyDataChange(timerData.copy(durationInMinutes = it.toInt()).toJson()) },
                    valueRange = 5f..180f,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.profile_edit_hide_stop_button_label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = timerData.hideStopButton,
                        onCheckedChange = { onStrategyDataChange(timerData.copy(hideStopButton = it).toJson()) },
                    )
                }
            }
        } else if (strategy.hasPauseMode) {
            val pauseData = StrategyPauseTimerData.fromJsonOrNull(strategyDataJson)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(text = stringResource(R.string.profile_edit_pause_duration_label, pauseData.pauseDurationInMinutes))
                Slider(
                    value = pauseData.pauseDurationInMinutes.toFloat(),
                    onValueChange = { onStrategyDataChange(pauseData.copy(pauseDurationInMinutes = it.toInt()).toJson()) },
                    valueRange = 1f..120f,
                )
            }
        } else if (!strategy.allowsTimedBreaks) {
            val softData = SoftUnblockStrategyData.fromJsonOrNull(strategyDataJson)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(text = stringResource(R.string.profile_edit_soft_unblock_access_label, softData.accessDurationInMinutes))
                Slider(
                    value = softData.accessDurationInMinutes.toFloat(),
                    onValueChange = { onStrategyDataChange(softData.copy(accessDurationInMinutes = it.toInt()).normalized.toJson()) },
                    valueRange = SoftUnblockStrategyData.DURATION_RANGE.first.toFloat()..SoftUnblockStrategyData.DURATION_RANGE.last.toFloat(),
                )
                Text(text = stringResource(R.string.profile_edit_soft_unblock_max_opens_label, softData.maximumUnblockCount))
                Slider(
                    value = softData.maximumUnblockCount.toFloat(),
                    onValueChange = { onStrategyDataChange(softData.copy(maximumUnblockCount = it.toInt()).normalized.toJson()) },
                    valueRange = SoftUnblockStrategyData.UNBLOCK_COUNT_RANGE.first.toFloat()..SoftUnblockStrategyData.UNBLOCK_COUNT_RANGE.last.toFloat(),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun AppsSection(blockedCount: Int, onOpenAppPicker: () -> Unit) {
    SectionCard(title = stringResource(R.string.profile_edit_apps_title)) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp), onClick = onOpenAppPicker) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (blockedCount == 1) {
                        stringResource(R.string.profile_edit_apps_count_singular)
                    } else {
                        stringResource(R.string.profile_edit_apps_count_plural, blockedCount)
                    },
                )
            }
        }
    }
}

@Composable
private fun WebsitesSection(
    domains: List<String>,
    allowModeDomains: Boolean,
    onAddClick: () -> Unit,
    onRemove: (String) -> Unit,
    onAllowModeChange: (Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.profile_edit_websites_title)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.profile_edit_websites_allow_mode_label), modifier = Modifier.weight(1f))
                Switch(checked = allowModeDomains, onCheckedChange = onAllowModeChange)
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                domains.forEach { domain ->
                    InputChip(selected = false, onClick = {}, label = { Text(domain) }, trailingIcon = {
                        IconButton(onClick = { onRemove(domain) }, modifier = Modifier.size(18.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_delete))
                        }
                    })
                }
            }

            OutlinedButton(onClick = onAddClick) { Text(stringResource(R.string.profile_edit_add_domain)) }
        }
    }
}

@Composable
private fun AddDomainDialog(error: String?, onConfirm: (String) -> Unit, onDismiss: () -> Unit, dismissOnSuccess: Boolean) {
    var value by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }

    LaunchedEffect(error) {
        if (attempted && error == null) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_edit_add_domain)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.profile_edit_domain_field_label)) },
                isError = error != null,
                supportingText = { if (error != null) Text(error) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { attempted = true; onConfirm(value) }) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ScheduleSection(
    enabled: Boolean,
    schedule: BlockedProfileSchedule?,
    onEnabledChange: (Boolean) -> Unit,
    onToggleDay: (com.foqos.android.data.model.Weekday) -> Unit,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.profile_edit_schedule_title)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.profile_edit_schedule_enable_label), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            if (enabled) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WEEKDAY_DISPLAY_ORDER.forEach { day ->
                        val selected = schedule?.days?.contains(day) == true
                        FilterChip(selected = selected, onClick = { onToggleDay(day) }, label = { Text(shortDayName(day)) })
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onStartClick, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.profile_edit_schedule_start, schedule?.startHour ?: 9, schedule?.startMinute ?: 0))
                    }
                    OutlinedButton(onClick = onEndClick, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.profile_edit_schedule_end, schedule?.endHour ?: 17, schedule?.endMinute ?: 0))
                    }
                }

                schedule?.takeIf { it.isConfigured }?.let {
                    Text(
                        text = formatScheduleSummary(it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BreaksSection(
    enabled: Boolean,
    durationMinutes: Int,
    allowMultiple: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDurationChange: (Int) -> Unit,
    onAllowMultipleChange: (Boolean) -> Unit,
) {
    val options = listOf(5, 10, 15, 30)
    SectionCard(title = stringResource(R.string.profile_edit_breaks_title)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.profile_edit_breaks_enable_label), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, minutes ->
                        SegmentedButton(
                            selected = durationMinutes == minutes,
                            onClick = { onDurationChange(minutes) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        ) { Text("${minutes}m") }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = stringResource(R.string.profile_edit_breaks_allow_multiple_label), modifier = Modifier.weight(1f))
                    Switch(checked = allowMultiple, onCheckedChange = onAllowMultipleChange)
                }
            }
        }
    }
}

@Composable
private fun SessionProtectionSection(
    strictMode: Boolean,
    blockAppInstallation: Boolean,
    disableBackgroundStops: Boolean,
    enableEmergencyUnblock: Boolean,
    onStrictModeChange: (Boolean) -> Unit,
    onBlockAppInstallationChange: (Boolean) -> Unit,
    onDisableBackgroundStopsChange: (Boolean) -> Unit,
    onEnableEmergencyUnblockChange: (Boolean) -> Unit,
) {
    SectionCard(title = stringResource(R.string.profile_edit_protection_title)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ToggleRow(
                title = stringResource(R.string.profile_edit_strict_mode_label),
                body = stringResource(R.string.profile_edit_strict_mode_body),
                checked = strictMode,
                onCheckedChange = onStrictModeChange,
            )
            ToggleRow(
                title = stringResource(R.string.profile_edit_block_installs_label),
                body = stringResource(R.string.profile_edit_block_installs_body),
                checked = blockAppInstallation,
                onCheckedChange = onBlockAppInstallationChange,
            )
            ToggleRow(
                title = stringResource(R.string.profile_edit_require_app_stop_label),
                body = stringResource(R.string.profile_edit_require_app_stop_body),
                checked = disableBackgroundStops,
                onCheckedChange = onDisableBackgroundStopsChange,
            )
            ToggleRow(
                title = stringResource(R.string.profile_edit_emergency_unblock_label),
                body = stringResource(R.string.profile_edit_emergency_unblock_body),
                checked = enableEmergencyUnblock,
                onCheckedChange = onEnableEmergencyUnblockChange,
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun WriteNfcTagDialog(
    viewModel: ProfileEditViewModel,
    hasPhysicalUnlocks: Boolean,
    profileName: String,
    onDismiss: () -> Unit,
) {
    var confirmedWarning by remember { mutableStateOf(!hasPhysicalUnlocks) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    if (!confirmedWarning) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.profile_edit_nfc_warning_title)) },
            text = { Text(stringResource(R.string.profile_edit_nfc_warning_body, profileName)) },
            confirmButton = { TextButton(onClick = { confirmedWarning = true }) { Text(stringResource(R.string.action_continue_anyway)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
        return
    }

    LaunchedEffect(Unit) {
        viewModel.nfcDispatchManager.setWaitingForScan(true)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { viewModel.nfcDispatchManager.setWaitingForScan(false) }
    }
    LaunchedEffect(Unit) {
        val tag = viewModel.nfcDispatchManager.rawTagScans.first()
        val profileId = viewModel.uiState.value.draft.id
        val result = NfcTagWriter.writeProfileTag(tag, profileId)
        status = when (result) {
            is com.foqos.android.service.nfc.WriteResult.Success -> "success"
            is com.foqos.android.service.nfc.WriteResult.Error -> result.message
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (val current = status) {
                null -> {
                    Text("📡", style = MaterialTheme.typography.displayMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.profile_edit_nfc_hold_tag), style = MaterialTheme.typography.titleMedium)
                }
                "success" -> {
                    Text("✅", style = MaterialTheme.typography.displayMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.profile_edit_nfc_success), style = MaterialTheme.typography.titleMedium)
                }
                else -> {
                    Text("⚠️", style = MaterialTheme.typography.displayMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(current, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        }
    }
}

@Composable
private fun ShowQrCodeDialog(profileId: String, profileName: String, onDismiss: () -> Unit) {
    val bitmap = remember(profileId) { QrCodeGenerator.profileQrBitmap(profileId, 512) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profileName) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(240.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.profile_edit_qr_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}
