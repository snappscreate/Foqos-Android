@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.foqos.android.ui.strategyflow

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foqos.android.data.model.SoftUnblockStrategyData
import com.foqos.android.data.model.StrategyPauseTimerData
import com.foqos.android.data.model.StrategyTimerData
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.domain.session.PendingStrategyAction
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.domain.strategy.StrategyInput
import com.foqos.android.domain.strategy.StrategyUiRequest
import com.foqos.android.service.nfc.NfcForegroundDispatchManager
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Pure UI constants/logic shared between the strategy-flow sheets and their tests - kept
 * Compose-free so they're directly unit-testable.
 */
object StrategyFlowUiDefaults {
    val TIMER_QUICK_MINUTES = listOf(15, 30, 45, 60, 90, 120)
    val PAUSE_QUICK_MINUTES = listOf(5, 10, 15, 30)

    /** `null` = "no reset" (unlimited allowance until the session ends). */
    val SOFT_UNBLOCK_RESET_OPTIONS: List<Int?> = listOf(null, 6, 12, 24)

    /**
     * Resolves the effective duration (minutes) for a timer/pause picker: the parsed custom-text
     * value if it's a positive integer, otherwise [fallbackMinutes] (the last quick-chip pick).
     */
    fun resolveMinutes(customText: String, fallbackMinutes: Int): Int =
        customText.trim().toIntOrNull()?.takeIf { it > 0 } ?: fallbackMinutes
}

/**
 * Mounts the live strategy-flow UI: collects [SessionManager.uiRequests] and renders whichever
 * modal the active [StrategyUiRequest] needs, feeding the result back via
 * [SessionManager.deliverInput]; also collects [SessionManager.errors] into a [Snackbar]. Mount
 * once at the root of the app (e.g. from `MainActivity`'s `setContent`).
 *
 * Flow collection/persistence wiring lives here and stays thin; the actual sheet content is
 * [StrategyUiRequestSheet], a flow-free composable that can be exercised directly in tests.
 */
@Composable
fun StrategyFlowHost(
    sessionManager: SessionManager,
    profileRepository: ProfileRepository,
    nfcDispatchManager: NfcForegroundDispatchManager,
    modifier: Modifier = Modifier,
) {
    var pending by remember { mutableStateOf<Pair<StrategyUiRequest, PendingStrategyAction>?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(sessionManager) {
        sessionManager.uiRequests.collect { pending = it }
    }
    LaunchedEffect(sessionManager) {
        sessionManager.errors.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    val current = pending

    // Thin NFC wiring: while a ScanNfc request is showing, latch the dispatcher into "waiting"
    // mode and suspend for the first scanned tag - cancelling (via key change / Cancel button
    // clearing `pending`) automatically un-latches it through the `finally` block.
    LaunchedEffect(current) {
        val (request, action) = current ?: return@LaunchedEffect
        if (request !is StrategyUiRequest.ScanNfc) return@LaunchedEffect

        nfcDispatchManager.setWaitingForScan(true)
        try {
            val content = nfcDispatchManager.tagScans.first()
            sessionManager.deliverInput(action, StrategyInput.Code(content.value, StrategyInput.CodeSource.NFC))
            pending = null
        } finally {
            nfcDispatchManager.setWaitingForScan(false)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (current != null) {
        val (request, action) = current
        StrategyUiRequestSheet(
            request = request,
            onCancel = { pending = null },
            onQrCode = { code ->
                scope.launch {
                    sessionManager.deliverInput(action, StrategyInput.Code(code, StrategyInput.CodeSource.QR))
                    pending = null
                }
            },
            onTimerConfirmed = { data ->
                scope.launch {
                    (action as? PendingStrategyAction.Start)?.let { persistTimerData(profileRepository, it.profileId, data) }
                    sessionManager.deliverInput(action, StrategyInput.TimerData(data))
                    pending = null
                }
            },
            onPauseConfirmed = { data ->
                scope.launch {
                    (action as? PendingStrategyAction.Start)?.let { persistPauseData(profileRepository, it.profileId, data) }
                    sessionManager.deliverInput(action, StrategyInput.PauseData(data))
                    pending = null
                }
            },
            onSoftUnblockConfirmed = { data ->
                scope.launch {
                    (action as? PendingStrategyAction.Start)?.let { persistSoftUnblockData(profileRepository, it.profileId, data) }
                    sessionManager.deliverInput(action, StrategyInput.SoftUnblockData(data))
                    pending = null
                }
            },
        )
    }
}

private suspend fun persistTimerData(profileRepository: ProfileRepository, profileId: String, data: StrategyTimerData) {
    val profile = profileRepository.getById(profileId) ?: return
    profileRepository.upsert(profile.copy(strategyDataJson = data.toJson(), updatedAt = Instant.now()))
}

private suspend fun persistPauseData(profileRepository: ProfileRepository, profileId: String, data: StrategyPauseTimerData) {
    val profile = profileRepository.getById(profileId) ?: return
    profileRepository.upsert(profile.copy(strategyDataJson = data.toJson(), updatedAt = Instant.now()))
}

private suspend fun persistSoftUnblockData(profileRepository: ProfileRepository, profileId: String, data: SoftUnblockStrategyData) {
    val profile = profileRepository.getById(profileId) ?: return
    profileRepository.upsert(profile.copy(strategyDataJson = data.toJson(), updatedAt = Instant.now()))
}

/**
 * Flow-free rendering of whatever sheet [request] needs. Kept separate from [StrategyFlowHost] so
 * it can be exercised in a `createComposeRule` test without any [SessionManager]/repository seam.
 */
@Composable
fun StrategyUiRequestSheet(
    request: StrategyUiRequest,
    onCancel: () -> Unit,
    onQrCode: (String) -> Unit = {},
    onTimerConfirmed: (StrategyTimerData) -> Unit = {},
    onPauseConfirmed: (StrategyPauseTimerData) -> Unit = {},
    onSoftUnblockConfirmed: (SoftUnblockStrategyData) -> Unit = {},
) {
    when (request) {
        is StrategyUiRequest.ScanNfc -> NfcScanFullScreenSheet(purpose = request.purpose, onCancel = onCancel)
        is StrategyUiRequest.ScanQr -> QrScanFullScreenSheet(purpose = request.purpose, onCode = onQrCode, onCancel = onCancel)
        is StrategyUiRequest.PickTimerDuration -> TimerDurationSheet(initial = request.initial, onConfirm = onTimerConfirmed, onCancel = onCancel)
        is StrategyUiRequest.PickPauseDuration -> PauseDurationSheet(initial = request.initial, onConfirm = onPauseConfirmed, onCancel = onCancel)
        is StrategyUiRequest.ConfigureSoftUnblock -> SoftUnblockConfigSheet(initial = request.initial, onConfirm = onSoftUnblockConfirmed, onCancel = onCancel)
        StrategyUiRequest.None -> Unit
    }
}

@Composable
private fun NfcScanFullScreenSheet(purpose: StrategyUiRequest.Purpose, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val transition = rememberInfiniteTransition(label = "nfc-pulse")
                val scale by transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
                    label = "nfc-pulse-scale",
                )
                val alpha by transition.animateFloat(
                    initialValue = 0.9f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
                    label = "nfc-pulse-alpha",
                )

                Box(
                    modifier = Modifier
                        .size((96 * scale).dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "📡", style = MaterialTheme.typography.displaySmall)
                }

                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "Hold your phone near the NFC tag",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (purpose == StrategyUiRequest.Purpose.START) "Scanning to start this profile" else "Scanning to stop this profile",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(32.dp))
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun QrScanFullScreenSheet(purpose: StrategyUiRequest.Purpose, onCode: (String) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                com.foqos.android.ui.qr.QrScannerScreen(
                    onCode = onCode,
                    modifier = Modifier.weight(1f),
                    title = if (purpose == StrategyUiRequest.Purpose.START) "Scan to start" else "Scan to stop",
                    subtitle = "Point your camera at the profile's QR code or barcode.",
                )
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun TimerDurationSheet(initial: StrategyTimerData, onConfirm: (StrategyTimerData) -> Unit, onCancel: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var minutes by remember(initial) { mutableIntStateOf(initial.durationInMinutes) }
    var customText by remember(initial) {
        mutableStateOf(if (initial.durationInMinutes in StrategyFlowUiDefaults.TIMER_QUICK_MINUTES) "" else initial.durationInMinutes.toString())
    }
    var hideStopButton by remember(initial) { mutableStateOf(initial.hideStopButton) }

    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "How long should this session last?", style = MaterialTheme.typography.titleLarge)

            DurationChipRow(
                options = StrategyFlowUiDefaults.TIMER_QUICK_MINUTES,
                selectedMinutes = minutes.takeIf { customText.isBlank() },
                onSelect = { minutes = it; customText = "" },
            )

            OutlinedTextField(
                value = customText,
                onValueChange = { customText = it },
                label = { Text("Custom minutes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Hide Stop button until timer ends")
                Switch(checked = hideStopButton, onCheckedChange = { hideStopButton = it })
            }

            SheetActions(
                onCancel = onCancel,
                onConfirm = {
                    val resolved = StrategyFlowUiDefaults.resolveMinutes(customText, minutes)
                    onConfirm(StrategyTimerData(durationInMinutes = resolved, hideStopButton = hideStopButton))
                },
            )
        }
    }
}

@Composable
private fun PauseDurationSheet(initial: StrategyPauseTimerData, onConfirm: (StrategyPauseTimerData) -> Unit, onCancel: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var minutes by remember(initial) { mutableIntStateOf(initial.pauseDurationInMinutes) }
    var customText by remember(initial) {
        mutableStateOf(if (initial.pauseDurationInMinutes in StrategyFlowUiDefaults.PAUSE_QUICK_MINUTES) "" else initial.pauseDurationInMinutes.toString())
    }

    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "How long should the pause last?", style = MaterialTheme.typography.titleLarge)

            DurationChipRow(
                options = StrategyFlowUiDefaults.PAUSE_QUICK_MINUTES,
                selectedMinutes = minutes.takeIf { customText.isBlank() },
                onSelect = { minutes = it; customText = "" },
            )

            OutlinedTextField(
                value = customText,
                onValueChange = { customText = it },
                label = { Text("Custom minutes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SheetActions(
                onCancel = onCancel,
                onConfirm = {
                    val resolved = StrategyFlowUiDefaults.resolveMinutes(customText, minutes)
                    onConfirm(StrategyPauseTimerData(pauseDurationInMinutes = resolved))
                },
            )
        }
    }
}

@Composable
private fun SoftUnblockConfigSheet(initial: SoftUnblockStrategyData, onConfirm: (SoftUnblockStrategyData) -> Unit, onCancel: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var accessMinutes by remember(initial) { mutableIntStateOf(initial.accessDurationInMinutes) }
    var maxOpens by remember(initial) { mutableIntStateOf(initial.maximumUnblockCount) }
    var resetHours by remember(initial) { mutableStateOf(initial.allowanceResetIntervalInHours) }

    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Temporary access settings", style = MaterialTheme.typography.titleLarge)

            Text(text = "Access duration: $accessMinutes min")
            Slider(
                value = accessMinutes.toFloat(),
                onValueChange = { accessMinutes = it.toInt() },
                valueRange = SoftUnblockStrategyData.DURATION_RANGE.first.toFloat()..SoftUnblockStrategyData.DURATION_RANGE.last.toFloat(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Max opens: $maxOpens")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        maxOpens = (maxOpens - 1).coerceIn(SoftUnblockStrategyData.UNBLOCK_COUNT_RANGE)
                    }) { Text("−") }
                    OutlinedButton(onClick = {
                        maxOpens = (maxOpens + 1).coerceIn(SoftUnblockStrategyData.UNBLOCK_COUNT_RANGE)
                    }) { Text("+") }
                }
            }

            Text(text = "Reset allowance every")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrategyFlowUiDefaults.SOFT_UNBLOCK_RESET_OPTIONS.forEach { hours ->
                    FilterChip(
                        selected = resetHours == hours,
                        onClick = { resetHours = hours },
                        label = { Text(if (hours == null) "None" else "${hours}h") },
                    )
                }
            }

            SheetActions(
                onCancel = onCancel,
                onConfirm = {
                    onConfirm(
                        SoftUnblockStrategyData(
                            accessDurationInMinutes = accessMinutes,
                            maximumUnblockCount = maxOpens,
                            allowanceResetIntervalInHours = resetHours,
                        ).normalized,
                    )
                },
            )
        }
    }
}

@Composable
private fun DurationChipRow(options: List<Int>, selectedMinutes: Int?, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = selectedMinutes == option,
                onClick = { onSelect(option) },
                label = { Text("${option}m") },
            )
        }
    }
}

@Composable
private fun SheetActions(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
        Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Confirm") }
    }
}
