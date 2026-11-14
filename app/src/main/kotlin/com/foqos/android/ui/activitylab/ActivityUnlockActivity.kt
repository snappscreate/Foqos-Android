package com.foqos.android.ui.activitylab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.ui.theme.FoqosTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Activity proof is never accepted from an Intent. Only a real Gemini verdict can pause. */
@AndroidEntryPoint
class ActivityUnlockActivity : ComponentActivity() {
    @Inject lateinit var sessionManager: SessionManager

    companion object {
        const val EXTRA_TEST = "test"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_COUNTDOWN = "countdown"
        const val EXTRA_FRONT = "front_camera"
        const val EXTRA_UNLIMITED = "unlimited_recording"
        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CONDITION_ID = "condition_id"
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val isTest = intent.getBooleanExtra(EXTRA_TEST, false)
        val profileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val conditionId = intent.getStringExtra(EXTRA_CONDITION_ID).orEmpty()
        val entry = if (!isTest) ActivityUnlockStore(this).conditions(profileId)
            .firstOrNull { it.id == conditionId } else null
        if (!isTest && (entry == null || sessionId.isBlank())) {
            finish()
            return
        }
        val configuredTarget = if (isTest) null else
            ActivityChallengeSetStore(this).progress(profileId, sessionId)?.let { progress ->
                ActivityChallengeSetStore(this).allSets(profileId)
                    .firstOrNull { it.id == progress.setId }?.targets?.get(conditionId)
            }
        val description = if (isTest) intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
            else entry!!.description
        val seconds = (if (isTest) intent.getIntExtra(EXTRA_SECONDS, 20)
            else entry!!.recordingSeconds).coerceIn(10, 120)
        val countdown = (if (isTest) intent.getIntExtra(EXTRA_COUNTDOWN, 5)
            else entry!!.countdownSeconds).coerceIn(0, 30)
        val legacyUnlimited = if (isTest) false else entry!!.unlimitedRecording
        val frontCamera = if (isTest) intent.getBooleanExtra(EXTRA_FRONT, true)
            else entry!!.frontCamera
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            FoqosTheme {
                ActivityUnlockCapture(
                    description = description,
                    seconds = seconds,
                    configuredTarget = configuredTarget,
                    legacyUnlimited = legacyUnlimited,
                    countdown = countdown,
                    frontCamera = frontCamera,
                    isTest = isTest,
                    onBack = { finish() },
                    onVerified = {
                        // Rechecks active session + selected condition, not just an old UI snapshot.
                        sessionManager.verifyActivityStep(profileId, sessionId, conditionId)
                    },
                    onFinished = { finish() },
                )
            }
        }
    }
}

/** A short cue on the alarm stream, audible in normal silent/vibrate mode when alarm volume
 * is enabled. Android Do Not Disturb and hardware audio policies remain authoritative. */
private fun captureSound(start: Boolean) {
    runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_ALARM, 75)
        tone.startTone(if (start) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 120)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 220L)
    }
}

/** The same capture state machine is used for test and real unlock attempts. */
private enum class CapturePhase { READY, COUNTDOWN, RECORDING, PAUSED, RESUME_COUNTDOWN, JUDGING }

@Composable
private fun ActivityUnlockCapture(
    description: String,
    seconds: Int,
    configuredTarget: ActivityTarget?,
    legacyUnlimited: Boolean,
    countdown: Int,
    frontCamera: Boolean,
    isTest: Boolean,
    onBack: () -> Unit,
    onVerified: suspend () -> ActivityProofResult,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val camera = remember { ActivityCamera(context) }
    var testKind by remember { mutableStateOf(ActivityTargetKind.COUNT) }
    var testAmountText by remember { mutableStateOf("30") }
    val testMaximum = if (testKind == ActivityTargetKind.COUNT) 300 else 180
    val testAmount = testAmountText.toIntOrNull()?.takeIf { it in 1..testMaximum }
    val target = if (isTest) testAmount?.let { ActivityTarget(testKind, it) } else configuredTarget
    val targetMinutes = target?.takeIf { it.kind == ActivityTargetKind.MINUTES }?.amount
    val shortTimed = targetMinutes != null && targetMinutes <= 1
    val unlimited = (targetMinutes != null && !shortTimed) ||
        (target == null && !isTest && legacyUnlimited)
    val continuousCount = target?.kind == ActivityTargetKind.COUNT
    val minimumCaptureMillis = targetMinutes?.times(60_000L)
    val autoDurationMillis = if (shortTimed) minimumCaptureMillis ?: 60_000L
        else seconds * 1000L
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var cameraReady by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(CapturePhase.READY) }
    var countdownRemaining by remember { mutableIntStateOf(0) }
    var recordingRemainingMs by remember { mutableLongStateOf(if (unlimited || continuousCount) 0L else autoDurationMillis) }
    var status by remember { mutableStateOf("撮影待機中") }
    var verdict by remember { mutableStateOf<ActivityVerdict?>(null) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    var resumeJob by remember { mutableStateOf<Job?>(null) }
    var videoBytes by remember { mutableStateOf<Long?>(null) }
    var judgementMillis by remember { mutableStateOf<Long?>(null) }
    var verdictCostYen by remember { mutableStateOf<Double?>(null) }
    var video by remember { mutableStateOf<File?>(null) }
    var activityElapsedMs by remember { mutableLongStateOf(0L) }
    var activityStartedAt by remember { mutableLongStateOf(0L) }
    var sampleCount by remember { mutableIntStateOf(0) }
    var userPaused by remember { mutableStateOf(false) }
    var samplingClip by remember { mutableStateOf(false) }
    var pauseStartedAt by remember { mutableLongStateOf(0L) }
    var pausedTotalMs by remember { mutableLongStateOf(0L) }
    fun activeElapsedNow(): Long {
        if (activityStartedAt == 0L) return 0L
        val now = SystemClock.elapsedRealtime()
        val currentPause = if (userPaused && pauseStartedAt != 0L) now - pauseStartedAt else 0L
        return (now - activityStartedAt - pausedTotalMs - currentPause).coerceAtLeast(0L)
    }
    fun pauseCapture() {
        if (userPaused) return
        camera.pauseRecording()
        userPaused = true
        pauseStartedAt = SystemClock.elapsedRealtime()
        phase = CapturePhase.PAUSED
        status = "撮影を一時停止中。活動時間には含めないよ。"
    }
    fun resumeCapture() {
        if (!userPaused) return
        pausedTotalMs += (SystemClock.elapsedRealtime() - pauseStartedAt).coerceAtLeast(0L)
        pauseStartedAt = 0L
        userPaused = false
        if (!unlimited || samplingClip) camera.resumeRecording()
        phase = CapturePhase.RECORDING
        status = "撮影を再開したよ。"
    }
    val latestCaptureJob by rememberUpdatedState(captureJob)
    val latestResumeJob by rememberUpdatedState(resumeJob)
    val latestVideo by rememberUpdatedState(video)

    DisposableEffect(camera) {
        onDispose { latestResumeJob?.cancel(); latestCaptureJob?.cancel(); camera.close(); latestVideo?.delete() }
    }
    LaunchedEffect(granted, owner, unlimited, shortTimed) {
        cameraReady = false
        if (granted) {
            try { camera.bind(owner, front = frontCamera, videoMode = true, intermittent = unlimited || shortTimed); cameraReady = true }
            catch (e: Exception) { status = "カメラを起動できない：${e.message}" }
        }
    }
    // Activity targets use the active clock; user-paused time never counts.
    LaunchedEffect(phase, userPaused) {
        if (phase == CapturePhase.RECORDING || phase == CapturePhase.PAUSED) {
            while (true) {
                recordingRemainingMs = camera.remainingRecordingMillis
                activityElapsedMs = activeElapsedNow()
                delay(100L)
            }
        }
    }
    val settings = remember { ActivityLabSettingsStore(context).load() }
    val credentials = remember { ActivityApiKeyStore(context) }
    val canRequest = if (settings.useRelay) settings.endpoint.startsWith("https://")
        else credentials.hasKey()
    val recordingSecondsLeft = ((recordingRemainingMs + 999L) / 1000L).coerceAtLeast(0L)
    val timeLabel = if (unlimited) "長時間・間欠撮影" else if (continuousCount) "回数判定・連続録画" else
        "%02d:%02d".format(recordingSecondsLeft / 60L, recordingSecondsLeft % 60L)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isTest) "動作判定テスト（解除なし）" else "AI動作チャレンジ",
                style = MaterialTheme.typography.titleLarge)
            Text(if (target != null) "${description} ${target.description()}" else description,
                style = MaterialTheme.typography.titleMedium)
            if (isTest) {
                Text("テストの達成条件", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    listOf(ActivityTargetKind.COUNT to "回数", ActivityTargetKind.MINUTES to "時間")
                        .forEach { (kind, label) ->
                            FilterChip(
                                selected = testKind == kind,
                                enabled = phase == CapturePhase.READY,
                                onClick = {
                                    testKind = kind
                                    testAmountText = if (kind == ActivityTargetKind.COUNT) "30" else "5"
                                },
                                label = { Text(label) },
                            )
                        }
                    Spacer(Modifier.weight(1f))
                    OutlinedTextField(
                        value = testAmountText,
                        onValueChange = { raw ->
                            if (raw.length <= 3 && raw.all(Char::isDigit)) testAmountText = raw
                        },
                        enabled = phase == CapturePhase.READY,
                        modifier = Modifier.width(108.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        suffix = { Text(if (testKind == ActivityTargetKind.COUNT) "回" else "分") },
                        isError = testAmount == null,
                    )
                }
                if (testAmount == null) Text("1〜${testMaximum}の数値を入力してね。",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(when {
                unlimited -> "時間判定：平均約${targetMinutes?.let { IntermittentCapturePlan.averageIntervalMillis(it) / 1000L } ?: 90}秒間隔で3秒ずつランダム撮影。設定時間で自動終了。一時停止中は時間に含めないよ。"
                shortTimed -> "1分以内の時間判定は連続撮影して、自動終了。一時停止中は時間に含めないよ。"
                continuousCount -> "回数判定：連続撮影。途中で一時停止・再開できるよ。必要な回数を終えたら手動で判定。"
                else -> "撮影を終了すると、それまでの映像で判定する。"
            }, style = MaterialTheme.typography.bodySmall)
            if (granted) {
                Box(modifier = Modifier.fillMaxWidth().height(260.dp).clipToBounds()) {
                    AndroidView(factory = { camera.preview },
                        modifier = Modifier.fillMaxSize().clipToBounds())
                    val overlayMessage = when (phase) {
                        CapturePhase.COUNTDOWN -> "撮影開始まで $countdownRemaining"
                        CapturePhase.RECORDING -> if (unlimited) "長時間の活動を記録中"
                            else if (continuousCount) "● 回数判定・連続録画中" else "● 録画中  残り $timeLabel"
                        CapturePhase.RESUME_COUNTDOWN -> "撮影再開まで $countdownRemaining"
                        CapturePhase.PAUSED -> "Ⅱ 一時停止中（活動時間はカウントしない）"
                        CapturePhase.JUDGING -> "撮影終了・AI判定中"
                        CapturePhase.READY -> null
                    }
                    overlayMessage?.let { message ->
                        Box(modifier = Modifier.fillMaxWidth().padding(12.dp),
                            contentAlignment = Alignment.Center) {
                            Text(message,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.82f), MaterialTheme.shapes.medium)
                                    .padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                }
            } else {
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                    Text("カメラの使用を許可")
                }
            }
            Text("${if (unlimited) "ランダム間欠撮影・480p・無音" else if (continuousCount) "連続撮影・手動終了" else if (shortTimed) "連続撮影・1分で自動終了" else "撮影上限 ${seconds}秒"} / 開始前 ${countdown}秒 / ${if (frontCamera) "前面" else "背面"}カメラ")
            if (phase == CapturePhase.RECORDING || phase == CapturePhase.PAUSED || phase == CapturePhase.RESUME_COUNTDOWN) {
                Text(if (unlimited || continuousCount || shortTimed)
                    "活動経過時間：%02d:%02d".format(
                        activityElapsedMs / 60_000L, (activityElapsedMs / 1000L) % 60L)
                    else "残り撮影時間：$timeLabel" +
                        if (phase != CapturePhase.RECORDING) "（カウント停止中）" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
            }
            Text(status, color = MaterialTheme.colorScheme.onBackground)
            if (!canRequest) Text("AI活動認識テストでGemini APIキーまたはHTTPS中継を設定してね。",
                color = MaterialTheme.colorScheme.error)
            when (phase) {
                CapturePhase.READY -> Button(
                    enabled = granted && cameraReady && canRequest && description.isNotBlank() && (!isTest || testAmount != null),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        captureJob = scope.launch {
                            verdict = null
                            videoBytes = null
                            judgementMillis = null
                            verdictCostYen = null
                            video?.delete()
                            video = null
                            recordingRemainingMs = if (unlimited || continuousCount) 0L else autoDurationMillis
                            activityElapsedMs = 0L
                            activityStartedAt = 0L
                            sampleCount = 0
                            userPaused = false
                            samplingClip = false
                            pauseStartedAt = 0L
                            pausedTotalMs = 0L
                            try {
                                phase = CapturePhase.COUNTDOWN
                                for (i in countdown downTo 1) {
                                    countdownRemaining = i
                                    status = "録画まであと${i}秒"
                                    delay(1000L)
                                }
                                countdownRemaining = 0
                                phase = CapturePhase.RECORDING
                                val sampleTimes = mutableListOf<Long>()
                                val activityStart = SystemClock.elapsedRealtime()
                                activityStartedAt = activityStart
                                captureSound(start = true)
                                status = if (unlimited && minimumCaptureMillis != null)
                                    "間欠撮影中。設定時間に達したら自動で判定するよ。"
                                    else "● 撮影開始！"
                                val recorded = if (unlimited) {
                                    // CameraX pause/resume keeps the sampled clips in one MP4.
                                    // No per-clip sound or timer reveals individual sample boundaries.
                                    coroutineScope {
                                        val recording = async { camera.recordFor(0L) }
                                        val sampleLoop = launch {
                                            // Keep pause time out of both the recording cadence and the goal clock.
                                            delay(350L)
                                            while (true) {
                                                while (userPaused) delay(100L)
                                                samplingClip = true
                                                sampleTimes.add(activeElapsedNow())
                                                sampleCount = sampleTimes.size
                                                var clipRemaining = IntermittentCapturePlan.CLIP_MILLIS
                                                while (clipRemaining > 0L) {
                                                    if (userPaused) {
                                                        delay(100L)
                                                    } else {
                                                        val step = minOf(100L, clipRemaining)
                                                        delay(step)
                                                        if (!userPaused) clipRemaining -= step
                                                    }
                                                }
                                                camera.pauseRecording()
                                                samplingClip = false
                                                var gapRemaining = if (targetMinutes != null)
                                                    IntermittentCapturePlan.nextGapMillis(targetMinutes)
                                                    else IntermittentCapturePlan.nextGapMillis()
                                                while (gapRemaining > 0L) {
                                                    if (userPaused) {
                                                        delay(100L)
                                                    } else {
                                                        val step = minOf(100L, gapRemaining)
                                                        delay(step)
                                                        if (!userPaused) gapRemaining -= step
                                                    }
                                                }
                                                while (userPaused) delay(100L)
                                                camera.resumeRecording()
                                            }
                                        }
                                        val elapsedTicker = launch {
                                            while (true) {
                                                activityElapsedMs = activeElapsedNow()
                                                if (minimumCaptureMillis != null &&
                                                    activityElapsedMs >= minimumCaptureMillis) {
                                                    status = "設定時間に達したよ。自動で判定するね。"
                                                    camera.finishRecordingNow()
                                                    break
                                                }
                                                delay(100L)
                                            }
                                        }
                                        try { recording.await() }
                                        finally { sampleLoop.cancel(); elapsedTicker.cancel() }
                                    }
                                } else {
                                    camera.recordFor(if (continuousCount) 0L else autoDurationMillis)
                                }
                                val elapsed = activeElapsedNow()
                                captureSound(start = false)
                                activityElapsedMs = elapsed
                                video = recorded
                                videoBytes = recorded.length()
                                recordingRemainingMs = 0L
                                val minimum = minimumCaptureMillis ?: if (unlimited)
                                    IntermittentCapturePlan.minimumDurationMillis(description) else null
                                // Allow minor CameraX finalization/clock differences at the boundary.
                                if (minimum != null && elapsed + 750L < minimum) {
                                    status = "指定された活動時間に達していないよ。必要：${minimum / 60_000L}分、経過：${elapsed / 60_000L}分。判定せず終了した。"
                                    return@launch
                                }
                                phase = CapturePhase.JUDGING
                                status = "撮影終了！ Geminiで判定中…"
                                val activityPrompt = if (unlimited)
                                    IntermittentCapturePlan.evidenceDescription("$description ${target?.description().orEmpty()}".trim(), elapsed, sampleTimes)
                                    else if (continuousCount)
                                        "${description} ${target?.description().orEmpty()}。動画の中で完了した回数を実際に数え、指示された回数未満なら必ず「不一致」。姿勢だけで回数を推定しない。途中の繰り返しを数え漏らしたり動画が不足している場合は「判断不能」。"
                                    else if (shortTimed)
                                        "${description} ${target?.description().orEmpty()}。指定時間の活動を映像で確認し、十分な証拠がない場合は判断不能。"
                                    else description
                                val judgementStartedAt = SystemClock.elapsedRealtime()
                                val result = if (settings.useRelay) {
                                    ActivityGeminiRelay.judge(
                                        settings.endpoint.trim(), credentials.loadRelayToken().orEmpty(),
                                        settings.geminiModel, activityPrompt, "video", emptyList(), recorded, 1000L)
                                } else {
                                    ActivityGeminiRelay.judgeDirect(
                                        credentials.load().orEmpty(), settings.geminiModel,
                                        activityPrompt, emptyList(), recorded, 1000L)
                                }
                                judgementMillis = android.os.SystemClock.elapsedRealtime() - judgementStartedAt
                                verdictCostYen = result.estimatedYen
                                verdict = result
                                if (result.status == "一致" && !isTest) {
                                    when (onVerified()) {
                                        ActivityProofResult.PAUSED -> {
                                            status = "セット達成！ 一時休止を開始したよ。"
                                            onFinished()
                                        }
                                        ActivityProofResult.STEP_COMPLETED -> {
                                            status = "動作達成！ セット内の残りの動作に挑戦してね。"
                                            onFinished()
                                        }
                                        ActivityProofResult.REJECTED ->
                                            status = "判定は一致したけど、有効なセッションまたは条件として認証できなかった。"
                                    }
                                } else {
                                    status = if (result.status == "一致") "判定成功（テストのため解除なし）"
                                        else "解除しない：${result.status}"
                                }
                            } catch (e: CancellationException) {
                                status = "判定せずに中止したよ"
                                throw e
                            } catch (e: Exception) {
                                runCatching {
                                    ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85).apply {
                                        startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ release() }, 750L)
                                    }
                                }
                                status = "撮影または判定エラー：${e.message}"
                            } finally {
                                countdownRemaining = 0
                                resumeJob?.cancel()
                                resumeJob = null
                                phase = CapturePhase.READY
                                video?.delete()
                                video = null
                            }
                        }
                    },
                ) { Text("カウントダウンを開始") }

                CapturePhase.COUNTDOWN -> {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { captureJob?.cancel() },
                    ) { Text("撮影を中止") }
                }

                CapturePhase.RECORDING, CapturePhase.PAUSED -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(modifier = Modifier.weight(1f), onClick = {
                            if (userPaused) resumeCapture() else pauseCapture()
                        }) { Text(if (userPaused) "▶ 再開" else "Ⅱ 一時停止") }
                        Button(modifier = Modifier.weight(1f), onClick = {
                            status = "撮影を終了して判定するよ"
                            camera.finishRecordingNow()
                        }) { Text("■ 撮影終了・判定") }
                    }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        captureJob?.cancel()
                    }) { Text("撮影を破棄して中止") }
                }

                CapturePhase.RESUME_COUNTDOWN -> {
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        resumeJob?.cancel()
                        resumeJob = null
                        countdownRemaining = 0
                        phase = CapturePhase.PAUSED
                        status = "ポーズ中。再開前のカウントダウンを取り消したよ"
                    }) { Text("再開を取り消す") }
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        resumeJob?.cancel()
                        captureJob?.cancel()
                    }) { Text("撮影を破棄して中止") }
                }

                CapturePhase.JUDGING -> OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { captureJob?.cancel() },
                ) { Text("判定を中止") }
            }
            verdict?.let { result ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("判定：${result.status}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(result.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (result.evidence.isNotBlank()) {
                            Text(result.evidence, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        Text("動画サイズ：${videoBytes?.let { "%.2f MB".format(it / 1_000_000.0) } ?: "未取得"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("AI判定時間：${judgementMillis?.let { "%.2f 秒".format(it / 1000.0) } ?: "未取得"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("推定APIコスト：${verdictCostYen?.let { "約 %.4f 円".format(it) } ?: "算出不可"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("コストはAPI使用量からの推定。実際の請求額とは異なる場合があるよ。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedButton(onClick = { captureJob?.cancel(); onBack() }) { Text("戻る") }
        }
    }
}
