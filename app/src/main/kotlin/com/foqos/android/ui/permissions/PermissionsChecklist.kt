package com.foqos.android.ui.permissions

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.foqos.android.R
import com.foqos.android.service.accessibility.ForegroundAppAccessibilityService
import com.foqos.android.service.nfc.NfcStatus
import com.foqos.android.service.session.PermissionChecks

/** One permission/settings item shown in [PermissionsChecklist]. */
private data class PermissionRowSpec(
    val title: String,
    val description: String,
    val isGranted: (android.content.Context) -> Boolean,
    val fixIntent: (android.content.Context) -> Intent?,
    val strongRecommendation: Boolean = false,
)

/**
 * Renders the app's permission checklist (accessibility, usage access, overlay, notifications,
 * exact alarms, battery optimization) with live status chips that re-check whenever the screen
 * resumes (e.g. returning from system Settings). Shared verbatim between [OnboardingScreen]'s
 * permissions page and [SettingsScreen]'s permission status list.
 */
@Composable
@SuppressLint("BatteryLife")
fun PermissionsChecklist(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refreshToken by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshToken++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { refreshToken++ }

    val specs = remember(context) {
        listOf(
            PermissionRowSpec(
                title = context.getString(R.string.permission_accessibility_title),
                description = context.getString(R.string.permission_accessibility_description),
                isGranted = { ctx -> PermissionChecks.isAccessibilityEnabled(ctx, ForegroundAppAccessibilityService::class.java) },
                fixIntent = { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                strongRecommendation = true,
            ),
            PermissionRowSpec(
                title = context.getString(R.string.permission_usage_access_title),
                description = context.getString(R.string.permission_usage_access_description),
                isGranted = { ctx -> PermissionChecks.hasUsageAccess(ctx) },
                fixIntent = { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
                strongRecommendation = true,
            ),
            PermissionRowSpec(
                title = context.getString(R.string.permission_overlay_title),
                description = context.getString(R.string.permission_overlay_description),
                isGranted = { ctx -> PermissionChecks.canDrawOverlays(ctx) },
                fixIntent = { ctx -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${ctx.packageName}".toUri()) },
            ),
        )
    }

    // Recomposes this whole block whenever `refreshToken` is bumped (screen resumed) - every
    // status check below reads live system state, so a full recompose is exactly what's needed.
    androidx.compose.runtime.key(refreshToken) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        specs.forEach { spec ->
            PermissionRow(
                title = spec.title,
                description = spec.description,
                granted = spec.isGranted(context),
                strongRecommendation = spec.strongRecommendation,
                onFix = { spec.fixIntent(context)?.let { context.startActivity(it) } },
            )
        }

        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        PermissionRow(
            title = stringResource(R.string.permission_notifications_title),
            description = stringResource(R.string.permission_notifications_description),
            granted = notificationsGranted,
            onFix = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(android.app.AlarmManager::class.java)
            val exactAlarmsGranted = alarmManager?.canScheduleExactAlarms() == true
            PermissionRow(
                title = stringResource(R.string.permission_exact_alarms_title),
                description = stringResource(R.string.permission_exact_alarms_description),
                granted = exactAlarmsGranted,
                onFix = { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())) },
            )
        }

        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
        val batteryExempt = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        PermissionRow(
            title = stringResource(R.string.permission_battery_title),
            description = stringResource(R.string.permission_battery_description),
            granted = batteryExempt,
            // Foqos's blocking session relies on a long-running foreground service and an
            // accessibility service; without a battery-optimization exemption, aggressive OEM
            // battery managers can kill both mid-session, silently ending the block. This is a
            // legitimate, disclosed, user-initiated use of the exemption (it is only requested
            // from this explicit settings row, never on launch), matching the accepted use case
            // in the Play Console content policy for apps whose core function depends on
            // uninterrupted background operation. Suppressed via @SuppressLint on this function.
            onFix = {
                val directIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
                if (directIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(directIntent)
                } else {
                    // Some OEM builds don't expose the direct per-app exemption dialog even when
                    // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is declared - fall back to the general
                    // "ignoring battery optimizations" list so the user can still find and grant it.
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
        )

        val nfcSupported = NfcStatus.isNfcSupported(context)
        if (nfcSupported) {
            PermissionRow(
                title = stringResource(R.string.permission_nfc_title),
                description = stringResource(R.string.permission_nfc_description),
                granted = NfcStatus.isNfcEnabled(context),
                onFix = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
            )
        }

        val accessibilityGranted = specs[0].isGranted(context)
        if (!accessibilityGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RestrictedSettingsNote(context = context)
        }
    }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onFix: () -> Unit,
    strongRecommendation: Boolean = false,
) {
    Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (strongRecommendation && !granted) {
                    Text(
                        text = stringResource(R.string.permission_strongly_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (!granted) {
                TextButton(onClick = onFix) { Text(stringResource(R.string.permission_fix_action)) }
            }
        }
    }
}

@Composable
private fun RestrictedSettingsNote(context: android.content.Context) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.permission_restricted_settings_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.permission_restricted_settings_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                context.startActivity(intent)
            }) { Text(stringResource(R.string.permission_app_info_action)) }
        }
    }
}
