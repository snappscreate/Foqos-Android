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
        val description = if (isTest) intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
            else entry!!.description
        val seconds = (if (isTest) intent.getIntExtra(EXTRA_SECONDS, 20)
            else entry!!.recordingSeconds).coerceIn(10, 120)
        val countdown = (if (isTest) intent.getIntExtra(EXTRA_COUNTDOWN, 5)
            else entry!!.countdownSeconds).coerceIn(0, 30)
        val unlimited = if (isTest) intent.getBooleanExtra(EXTRA_UNLIMITED, false)
            else entry!!.unlimitedRecording
        val frontCamera = if (isTest) intent.getBooleanExtra(EXTRA_FRONT, true)
            else entry!!.frontCamera
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            FoqosTheme {
                ActivityUnlockCapture(
                    description = description,
                    seconds = seconds,
                    unlimited = unlimited,
                    countdown = countdown,
                    frontCamera = frontCamera,
                    isTest = isTest,
                    onBack = { finish() },
                    onVerified = {
                        // Rechecks active session + selected condition, not just an old UI snapshot.
                        sessionManager.pauseWithVerifiedActivity(profileId, sessionId, conditionId)
                    },
                    onFinished = { finish() },
                )
            }
        }
    }
}

/** The same capture state machine is used for test and real unlock attempts. */
private enum class CapturePhase { READY, COUNTDOWN, RECORDING, PAUSED, RESUME_COUNTDOWN, JUDGING }

@Composable
private fun ActivityUnlockCapture(
    description: String,
    seconds: Int,
    unlimited: Boolean,
    countdown: Int,
    frontCamera: Boolean,
    isTest: Boolean,
    onBack: () -> Unit,
    onVerified: suspend () -> Boolean,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val camera = remember { ActivityCamera(context) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var cameraReady by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(CapturePhase.READY) }
    var countdownRemaining by remember { mutableIntStateOf(0) }
    var recordingRemainingMs by remember { mutableLongStateOf(if (unlimited) 0L else seconds * 1000L) }
    var status by remember { mutableStateOf("撮影待機中") }
    var verdict by remember { mutableStateOf<ActivityVerdict?>(null) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    var resumeJob by remember { mutableStateOf<Job?>(null) }
    var videoBytes by remember { mutableStateOf<Long?>(null) }
    var judgementMillis by remember { mutableStateOf<Long?>(null) }
    var verdictCostYen by remember { mutableStateOf<Double?>(null) }
    var video by remember { mutableStateOf<File?>(null) }
    val latestCaptureJob by rememberUpdatedState(captureJob)
    val latestResumeJob by rememberUpdatedState(resumeJob)
    val latestVideo by rememberUpdatedState(video)

    DisposableEffect(camera) {
        onDispose { latestResumeJob?.cancel(); latestCaptureJob?.cancel(); camera.close(); latestVideo?.delete() }
    }
    LaunchedEffect(granted, owner) {
        cameraReady = false
        if (granted) {
            try { camera.bind(owner, front = frontCamera, videoMode = true); cameraReady = true }
            catch (e: Exception) { status = "カメラを起動できない：${e.message}" }
        }
    }
    // CameraX timer is based on elapsedRealtime and is suspended on pause.
    // Polling here affects only the label, not the actual recording duration.
    LaunchedEffect(phase) {
        if (phase == CapturePhase.RECORDING || phase == CapturePhase.PAUSED) {
            while (true) {
                recordingRemainingMs = camera.remainingRecordingMillis
                delay(100L)
            }
        }
    }
    val settings = remember { ActivityLabSettingsStore(context).load() }
    val credentials = remember { ActivityApiKeyStore(context) }
    val canRequest = if (settings.useRelay) settings.endpoint.startsWith("https://")
        else credentials.hasKey()
    val recordingSecondsLeft = ((recordingRemainingMs + 999L) / 1000L).coerceAtLeast(0L)
    val timeLabel = if (unlimited) "上限なし（手動終了）" else
        "%02d:%02d".format(recordingSecondsLeft / 60L, recordingSecondsLeft % 60L)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isTest) "動作判定テスト（解除なし）" else "AI動作チャレンジ",
                style = MaterialTheme.typography.titleLarge)
            Text(description, style = MaterialTheme.typography.titleMedium)
            Text("ポーズ中は録画時間を消費しない。撮影を終了すると、それまでの映像で判定する。",
                style = MaterialTheme.typography.bodySmall)
            if (granted) {
                Box(modifier = Modifier.fillMaxWidth().height(260.dp).clipToBounds()) {
                    AndroidView(factory = { camera.preview },
                        modifier = Modifier.fillMaxSize().clipToBounds())
                    val overlayMessage = when (phase) {
                        CapturePhase.COUNTDOWN -> "撮影開始まで $countdownRemaining"
                        CapturePhase.RECORDING -> "● 録画中  残り $timeLabel"
                        CapturePhase.RESUME_COUNTDOWN -> "撮影再開まで $countdownRemaining"
                        CapturePhase.PAUSED -> "一時休止中  残り $timeLabel"
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
            Text("${if (unlimited) "録画時間制限なし" else "撮影上限 ${seconds}秒"} / 開始前 ${countdown}秒 / ${if (frontCamera) "前面" else "背面"}カメラ")
            if (phase == CapturePhase.RECORDING || phase == CapturePhase.PAUSED || phase == CapturePhase.RESUME_COUNTDOWN) {
                Text("残り撮影時間：$timeLabel" +
                    if (phase != CapturePhase.RECORDING) "（カウント停止中）" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold)
            }
            Text(status, color = MaterialTheme.colorScheme.onBackground)
            if (!canRequest) Text("AI活動認識テストでGemini APIキーまたはHTTPS中継を設定してね。",
                color = MaterialTheme.colorScheme.error)
            when (phase) {
                CapturePhase.READY -> Button(
                    enabled = granted && cameraReady && canRequest && description.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        captureJob = scope.launch {
                            verdict = null
                            videoBytes = null
                            judgementMillis = null
                            verdictCostYen = null
                            video?.delete()
                            video = null
                            recordingRemainingMs = if (unlimited) 0L else seconds * 1000L
                            try {
                                phase = CapturePhase.COUNTDOWN
                                for (i in countdown downTo 1) {
                                    countdownRemaining = i
                                    status = "録画まであと${i}秒"
                                    delay(1000L)
                                }
                                countdownRemaining = 0
                                phase = CapturePhase.RECORDING
                                status = "● 撮影開始！"
                                val recorded = camera.recordFor(if (unlimited) 0L else seconds * 1000L)
                                video = recorded
                                videoBytes = recorded.length()
                                recordingRemainingMs = 0L
                                phase = CapturePhase.JUDGING
                                status = "撮影終了！ Geminiで判定中…"
                                val judgementStartedAt = android.os.SystemClock.elapsedRealtime()
                                val result = if (settings.useRelay) {
                                    ActivityGeminiRelay.judge(
                                        settings.endpoint.trim(), credentials.loadRelayToken().orEmpty(),
                                        settings.geminiModel, description, "video", emptyList(), recorded, 1000L)
                                } else {
                                    ActivityGeminiRelay.judgeDirect(
                                        credentials.load().orEmpty(), settings.geminiModel,
                                        description, emptyList(), recorded, 1000L)
                                }
                                judgementMillis = android.os.SystemClock.elapsedRealtime() - judgementStartedAt
                                verdictCostYen = result.estimatedYen
                                verdict = result
                                if (result.status == "一致" && !isTest) {
                                    val paused = onVerified()
                                    status = if (paused) "判定成功。一時休止を開始したよ。" else
                                        "判定は一致したけど、セッションが終了・変更されていたため一時休止できなかった。"
                                    if (paused) onFinished()
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
                            if (phase == CapturePhase.RECORDING) {
                                camera.pauseRecording()
                                recordingRemainingMs = camera.remainingRecordingMillis
                                phase = CapturePhase.PAUSED
                                status = "撮影を一時休止中。再開で続きを録画するよ"
                            } else {
                                phase = CapturePhase.RESUME_COUNTDOWN
                                resumeJob?.cancel()
                                resumeJob = scope.launch {
                                    for (i in countdown downTo 1) {
                                        countdownRemaining = i
                                        status = "撮影再開まであと${i}秒"
                                        delay(1000L)
                                    }
                                    countdownRemaining = 0
                                    camera.resumeRecording()
                                    phase = CapturePhase.RECORDING
                                    status = "● 撮影を再開！"
                                }
                            }
                        }) { Text(if (phase == CapturePhase.PAUSED) "▶ 再開" else "Ⅱ ポーズ") }
                        Button(modifier = Modifier.weight(1f), onClick = {
                            status = "撮影終了。撮影済みの映像を判定するよ"
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
