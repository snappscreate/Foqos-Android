package com.foqos.android.ui.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.foqos.android.service.session.GlobalSessionProtectionStore
import com.foqos.android.service.session.FocusUsageCounterStore
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.foqos.android.ui.activitylab.ProfileUnlockPolicyStore
import com.foqos.android.ui.activitylab.ActivityUnlockStore
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foqos.android.R
import com.foqos.android.domain.session.SessionTimeCalculator
import com.foqos.android.domain.strategy.StrategyRegistry
import com.foqos.android.domain.strategy.showStopButton
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Full-screen active-session view: animated gradient background, big elapsed/remaining timer,
 * rotating focus message, and Break/Stop/Emergency actions. Shown when a session starts (from
 * [com.foqos.android.ui.navigation.FoqosNavHost]) or opened manually from Home's pinned bar.
 * Automatically closes itself (via [onClosed]) once the session ends.
 */
@Composable
fun ActiveSessionScreen(
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActiveSessionViewModel = hiltViewModel(),
) {
    val session by viewModel.activeSession.collectAsStateWithLifecycle()
    val profile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val emergencyRemaining by viewModel.emergencyUnblocksRemaining.collectAsStateWithLifecycle()

    LaunchedEffect(session) {
        if (session == null) onClosed()
    }

    val currentSession = session
    val currentProfile = profile
    if (currentSession == null || currentProfile == null) return

    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(currentSession.id) {
        while (true) {
            now = Instant.now()
            delay(1000)
        }
    }

    val focusMessages = stringArrayResource(R.array.focus_messages)
    var messageIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentSession.id) {
        while (true) {
            delay(20_000)
            messageIndex = (messageIndex + 1) % focusMessages.size
        }
    }

    val contextForReservation = LocalContext.current
    val strategy = StrategyRegistry.byId(currentProfile.strategyId)
    val usageCounter = remember(currentSession.id) {
        FocusUsageCounterStore(contextForReservation)
    }
    val elapsedFocusTime = Duration.ofMillis(usageCounter.elapsedMillis(currentSession.id))
    val displayedTime = if (currentSession.isPauseActive(now)) {
        // Reward-based and booked pauses may have a duration different from the
        // legacy strategy timer. Read the actual duration recorded for this session.
        val pauseMinutes = ActivityUnlockStore(contextForReservation).pauseMinutes(currentSession.id)
        val pausedAt = currentSession.pauseStartTime
        if (pauseMinutes != null && pausedAt != null) {
            val remaining = Duration.between(now, pausedAt.plusSeconds(pauseMinutes * 60L))
            if (remaining.isNegative) Duration.ZERO else remaining
        } else SessionTimeCalculator.displayedTime(currentSession, currentProfile, elapsedFocusTime, now)
    } else SessionTimeCalculator.displayedTime(currentSession, currentProfile, elapsedFocusTime, now)
    val isCountdown = SessionTimeCalculator.expectedEndTime(currentSession, currentProfile, now) != null
    val isBreakActive = currentSession.isBreakActive(now)
    val isPauseActive = currentSession.isPauseActive(now)
    val breakAvailable = currentProfile.enableBreaks &&
        SessionTimeCalculator.allowsTimedBreaks(currentProfile) &&
        SessionTimeCalculator.isBreakAvailable(currentSession, currentProfile, now) &&
        !isBreakActive
    val canShowStop = showStopButton(currentProfile, elapsedFocusTime.seconds)

    var showBreakSheet by remember { mutableStateOf(false) }
    var showEmergencySheet by remember { mutableStateOf(false) }
    var showReservationSheet by remember { mutableStateOf(false) }
    val minReservationDelay = remember(currentProfile.id) {
        ProfileUnlockPolicyStore(
            // The application context is stable across recompositions.
            context = contextForReservation
        ).read(currentProfile.id).reservationMinimumDelayMinutes
    }

    val infiniteTransition = rememberInfiniteTransition(label = "gradient")
    val gradientProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse),
        label = "gradient-progress",
    )
    val topColor by animateColorAsState(
        targetValue = androidx.compose.ui.graphics.lerp(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            gradientProgress,
        ),
        label = "top-color",
    )

    Surface(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(topColor, Color(0xFF0B0B10)))),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentProfile.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isBreakActive) {
                            val remaining = SessionTimeCalculator.remainingBreakAllowance(currentSession, currentProfile, now)
                            StatusChip(stringResource(R.string.active_session_break_active_chip, formatDuration(remaining)))
                        }
                        if (isPauseActive) {
                            StatusChip(stringResource(R.string.active_session_pause_active_chip))
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatDuration(displayedTime),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = stringResource(
                            if (isCountdown) R.string.active_session_remaining_label else R.string.active_session_elapsed_label,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = focusMessages.getOrElse(messageIndex) { focusMessages.first() },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (breakAvailable) {
                        OutlinedButton(
                            onClick = { showBreakSheet = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) { Text(stringResource(R.string.active_session_break_action)) }
                    }
                    if (isBreakActive) {
                        OutlinedButton(
                            onClick = viewModel::endBreak,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) { Text(stringResource(R.string.active_session_end_break_action)) }
                    }
                    if (!isPauseActive) {
                        val reservation = viewModel.pauseReservation(currentProfile.id)
                        if (reservation == null) {
                            OutlinedButton(
                                onClick = { showReservationSheet = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            ) { Text("一時休止を予約") }
                        } else {
                            val until = (reservation.startAtMillis - now.toEpochMilli()).coerceAtLeast(0L)
                            Text("一時休止予約：あと ${until / 3_600_000}時間 ${(until / 60_000) % 60}分",
                                color = Color.White)
                            TextButton(onClick = { viewModel.cancelPauseReservation(currentProfile.id) }) {
                                Text("予約を取り消す", color = Color.White)
                            }
                        }
                    }
                    if (canShowStop) {
                        Button(
                            onClick = viewModel::stopSession,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        ) { Text(stringResource(R.string.active_session_stop_action)) }
                    }
                    if (GlobalSessionProtectionStore(LocalContext.current).readFor(currentProfile).enableEmergencyUnblock) {
                        TextButton(
                            onClick = { showEmergencySheet = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.7f)),
                        ) { Text(stringResource(R.string.active_session_emergency_action)) }
                    }
                }
            }
        }
    }

    if (showReservationSheet) {
        var afterHours by remember(currentProfile.id) { mutableStateOf((minReservationDelay / 60).toString()) }
        var afterMinutes by remember(currentProfile.id) { mutableStateOf((minReservationDelay % 60).toString()) }
        var pauseHours by remember(currentProfile.id) { mutableStateOf("0") }
        var pauseMinutes by remember(currentProfile.id) { mutableStateOf("20") }
        val delayTotal = afterHours.toIntOrNull()?.let { hours ->
            afterMinutes.toIntOrNull()?.let { minutes ->
                if (hours in 0..168 && minutes in 0..59) hours * 60 + minutes else null
            }
        }
        val pauseTotal = pauseHours.toIntOrNull()?.let { hours ->
            pauseMinutes.toIntOrNull()?.let { minutes ->
                if (hours in 0..24 && minutes in 0..59) hours * 60 + minutes else null
            }
        }
        val valid = delayTotal != null && delayTotal in minReservationDelay..10080 &&
            pauseTotal != null && pauseTotal in 1..1440
        AlertDialog(
            onDismissRequest = { showReservationSheet = false },
            title = { Text("一時休止予約") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("最低待機時間：${minReservationDelay / 60}時間${minReservationDelay % 60}分")
                    Text("何時間何分後に休止を開始する？")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = afterHours, onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) afterHours = it },
                            label = { Text("時間後") }, modifier = Modifier.weight(1f),
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(
                            value = afterMinutes, onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) afterMinutes = it },
                            label = { Text("分後") }, modifier = Modifier.weight(1f),
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Text("休止する長さ")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = pauseHours, onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) pauseHours = it },
                            label = { Text("時間") }, modifier = Modifier.weight(1f),
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(
                            value = pauseMinutes, onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) pauseMinutes = it },
                            label = { Text("分") }, modifier = Modifier.weight(1f),
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Text("予約した時刻になると自動的に一時休止する。待機中は通常どおりブロックする。")
                    if (!valid) Text("待機時間は設定した最低時間以上、休止時間は1分〜24時間にしてね。",
                        color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    viewModel.reservePause(currentProfile.id, delayTotal!!, pauseTotal!!)
                    showReservationSheet = false
                }) { Text("予約する") }
            },
            dismissButton = {
                TextButton(onClick = { showReservationSheet = false }) { Text("キャンセル") }
            },
        )
    }

    if (showBreakSheet) {
        val remaining = SessionTimeCalculator.remainingBreakAllowance(currentSession, currentProfile, now)
        AlertDialog(
            onDismissRequest = { showBreakSheet = false },
            title = { Text(stringResource(R.string.active_session_break_sheet_title)) },
            text = { Text(stringResource(R.string.active_session_break_sheet_body, formatDuration(remaining))) },
            confirmButton = {
                TextButton(onClick = { viewModel.startBreak(); showBreakSheet = false }) {
                    Text(stringResource(R.string.active_session_break_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { showBreakSheet = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (showEmergencySheet) {
        EmergencyUnblockDialog(
            remaining = emergencyRemaining,
            onConfirm = { viewModel.emergencyUnblock(); showEmergencySheet = false },
            onDismiss = { showEmergencySheet = false },
        )
    }
}

@Composable
private fun StatusChip(text: String) {
    SuggestionChip(onClick = {}, label = { Text(text) })
}

@Composable
private fun EmergencyUnblockDialog(remaining: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var tapCount by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.active_session_emergency_sheet_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.active_session_emergency_sheet_body, remaining))
                if (remaining <= 0) {
                    Text(stringResource(R.string.active_session_emergency_none_left), color = MaterialTheme.colorScheme.error)
                } else if (tapCount > 0) {
                    Text(stringResource(R.string.active_session_emergency_progress, tapCount))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = remaining > 0,
                onClick = {
                    tapCount++
                    if (tapCount >= 3) onConfirm()
                },
            ) { Text(stringResource(R.string.active_session_emergency_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun formatDuration(duration: Duration): String {
    val safe = if (duration.isNegative) Duration.ZERO else duration
    val totalSeconds = safe.seconds
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
    }
}
