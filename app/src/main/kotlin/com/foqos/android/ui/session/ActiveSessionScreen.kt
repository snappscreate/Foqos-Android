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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

    val strategy = StrategyRegistry.byId(currentProfile.strategyId)
    val elapsedFocusTime = SessionTimeCalculator.elapsedFocusTime(currentSession, currentProfile, now)
    val displayedTime = SessionTimeCalculator.displayedTime(currentSession, currentProfile, elapsedFocusTime, now)
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
                    if (canShowStop) {
                        Button(
                            onClick = viewModel::stopSession,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        ) { Text(stringResource(R.string.active_session_stop_action)) }
                    }
                    if (currentProfile.enableEmergencyUnblock) {
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
