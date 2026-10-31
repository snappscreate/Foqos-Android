@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.foqos.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foqos.android.BuildConfig
import com.foqos.android.R
import com.foqos.android.service.diagnostics.CrashLogger
import com.foqos.android.service.nfc.NfcStatus
import com.foqos.android.ui.permissions.PermissionsChecklist
import com.foqos.android.ui.theme.PRESET_SEED_COLORS
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenExport: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showResetConfirm by remember { mutableStateOf(false) }
    var showCrashLogs by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.action_cancel)) }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsSection(title = stringResource(R.string.settings_theme_title)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PRESET_SEED_COLORS.forEachIndexed { index, color ->
                        ColorSwatch(
                            color = color,
                            selected = uiState.themeColorIndex == index,
                            onClick = { viewModel.setThemeColorIndex(index) },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DarkModeChip(stringResource(R.string.settings_dark_mode_system), "system", uiState.darkModeOverride, viewModel::setDarkModeOverride)
                    DarkModeChip(stringResource(R.string.settings_dark_mode_light), "light", uiState.darkModeOverride, viewModel::setDarkModeOverride)
                    DarkModeChip(stringResource(R.string.settings_dark_mode_dark), "dark", uiState.darkModeOverride, viewModel::setDarkModeOverride)
                }
            }

            SettingsSection(title = "Location-based blocking") {
                LocationAutomationSection()
            }

            SettingsSection(title = stringResource(R.string.settings_permissions_title)) {
                PermissionsChecklist(modifier = Modifier.padding(16.dp))
            }

            SettingsSection(title = stringResource(R.string.settings_emergency_title)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.settings_emergency_remaining, uiState.emergencyRemaining))
                    Text(stringResource(R.string.settings_emergency_reset_interval), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 4, 6, 8).forEach { weeks ->
                            FilterChip(
                                selected = uiState.emergencyResetWeeks == weeks,
                                onClick = { viewModel.setEmergencyResetWeeks(weeks) },
                                label = { Text("$weeks w") },
                            )
                        }
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_data_title)) {
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp), onClick = onOpenExport) {
                    Text(
                        text = stringResource(R.string.settings_export_data),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_danger_title)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { showResetConfirm = true }) {
                        Text(stringResource(R.string.settings_reset_blocking_state), color = MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(R.string.settings_crash_logs_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showCrashLogs = true }) {
                        Text(stringResource(R.string.settings_crash_logs_action))
                    }
                }
            }

            SettingsSection(title = stringResource(R.string.settings_about_title)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME) + " / Build " + BuildConfig.VERSION_CODE + " / " + BuildConfig.GIT_REVISION, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_attribution),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.resetAllState(); showResetConfirm = false }) {
                    Text(stringResource(R.string.settings_reset_blocking_state), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showCrashLogs) {
        CrashLogsDialog(onDismiss = { showCrashLogs = false })
    }
}

/**
 * Lists the (at most 5) on-device crash logs written by [CrashLogger] and lets the user view/share
 * one via a standard [android.content.Intent.ACTION_SEND] text share. A plain [Column] (not
 * [androidx.compose.foundation.lazy.LazyColumn]) is deliberate: this list is always tiny (<= 5
 * items) and AlertDialog's content slot is already height-bounded, so a lazy list would add
 * nothing but the risk of the nested-scrollable crash fixed in PhysicalUnlockEditor.
 */
@Composable
private fun CrashLogsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<File?>(null) }
    val logs = remember { CrashLogger.listCrashLogs(context) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    val viewing = selected
    if (viewing != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(viewing.name) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(text = viewing.readText(), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, viewing.readText())
                    }
                    context.startActivity(android.content.Intent.createChooser(shareIntent, viewing.name))
                }) { Text(stringResource(R.string.settings_crash_logs_share_action)) }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.action_done)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_crash_logs_title)) },
        text = {
            if (logs.isEmpty()) {
                Text(stringResource(R.string.settings_crash_logs_empty))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    logs.forEach { file ->
                        TextButton(onClick = { selected = file }) {
                            Text(dateFormat.format(Date(file.lastModified())))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp))
            content()
        }
    }
}

@Composable
private fun ColorSwatch(color: Color?, selected: Boolean, onClick: () -> Unit) {
    val displayColor = color ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(displayColor, CircleShape)
            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
    }
}

@Composable
private fun DarkModeChip(label: String, value: String, selected: String, onSelect: (String) -> Unit) {
    FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
}
