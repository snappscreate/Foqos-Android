package com.foqos.android.ui.activitylab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.draw.clipToBounds
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
            else entry!!.recordingSeconds).coerceIn(10, 60)
        val countdown = (if (isTest) intent.getIntExtra(EXTRA_COUNTDOWN, 5)
            else entry!!.countdownSeconds).coerceIn(0, 30)
        val frontCamera = if (isTest) intent.getBooleanExtra(EXTRA_FRONT, true)
            else entry!!.frontCamera
        setContent {
            FoqosTheme {
                ActivityUnlockCapture(
                    description = description,
                    seconds = seconds,
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

@Composable
private fun ActivityUnlockCapture(
    description: String,
    seconds: Int,
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
    var busy by remember { mutableStateOf(false) }
    var remaining by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf("撮影待機中") }
    var verdict by remember { mutableStateOf<ActivityVerdict?>(null) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    var video by remember { mutableStateOf<File?>(null) }
    val latestCaptureJob by rememberUpdatedState(captureJob)
    val latestVideo by rememberUpdatedState(video)
    DisposableEffect(camera) {
        onDispose { latestCaptureJob?.cancel(); camera.close(); latestVideo?.delete() }
    }
    LaunchedEffect(granted, owner) {
        cameraReady = false
        if (granted) {
            try { camera.bind(owner, front = frontCamera, videoMode = true); cameraReady = true }
            catch (e: Exception) { status = "カメラを起動できない：${e.message}" }
        }
    }
    val settings = remember { ActivityLabSettingsStore(context).load() }
    val credentials = remember { ActivityApiKeyStore(context) }
    val canRequest = if (settings.useRelay) settings.endpoint.startsWith("https://")
        else credentials.hasKey()
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (isTest) "動作判定テスト（解除なし）" else "AI動作チャレンジ",
            style = MaterialTheme.typography.titleLarge)
        Text(description, style = MaterialTheme.typography.titleMedium)
        Text("動画は判定時のみ送信し、判定後に端末の一時ファイルを削除する。", style = MaterialTheme.typography.bodySmall)
        if (granted) {
            Box(modifier = Modifier.fillMaxWidth().height(220.dp).clipToBounds()) {
                AndroidView(factory = { camera.preview }, modifier = Modifier.fillMaxSize().clipToBounds())
                remaining?.let { Text("撮影開始まで ${it} 秒", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(16.dp)) }
            }
        } else {
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("カメラの使用を許可") }
        }
        Text("録画 ${seconds}秒 / 開始前 ${countdown}秒 / ${if (frontCamera) "前面" else "背面"}カメラ")
        Text(status)
        if (!canRequest) Text("AI活動認識テストでGemini APIキーまたはHTTPS中継を設定してね。",
            color = MaterialTheme.colorScheme.error)
        if (!busy) {
            Button(enabled = granted && cameraReady && canRequest && description.isNotBlank(),
                modifier = Modifier.fillMaxWidth(), onClick = {
                captureJob = scope.launch {
                    busy = true
                    verdict = null
                    video?.delete(); video = null
                    try {
                        for (i in countdown downTo 1) {
                            remaining = i
                            status = "録画まであと${i}秒"
                            delay(1_000L)
                        }
                        remaining = null
                        status = "動画を撮影中…"
                        val recorded = camera.recordFor(seconds * 1000L)
                        video = recorded
                        status = "Geminiで判定中…"
                        val result = if (settings.useRelay) {
                            ActivityGeminiRelay.judge(
                                settings.endpoint.trim(), credentials.loadRelayToken().orEmpty(),
                                settings.geminiModel, description, "video", emptyList(), recorded, 1000L)
                        } else {
                            ActivityGeminiRelay.judgeDirect(
                                credentials.load().orEmpty(), settings.geminiModel,
                                description, emptyList(), recorded, 1000L)
                        }
                        verdict = result
                        if (result.status == "一致" && !isTest) {
                            val paused = onVerified()
                            status = if (paused) "判定成功。一時停止を開始したよ。" else
                                "判定は一致したけど、セッションが終了・変更されていたため一時停止できなかった。"
                            if (paused) onFinished()
                        } else {
                            status = if (result.status == "一致") "判定成功（テストのため解除なし）"
                                else "解除しない：${result.status}"
                        }
                    } catch (e: CancellationException) {
                        status = "キャンセルしたよ"
                        throw e
                    } catch (e: Exception) {
                        status = "撮影または判定エラー：${e.message}"
                    } finally {
                        remaining = null
                        busy = false
                        video?.delete()
                        video = null
                    }
                }
            }) { Text("カウントダウンを開始") }
        } else {
            OutlinedButton(onClick = { captureJob?.cancel() }) { Text("撮影・判定を中止") }
        }
        verdict?.let {
            Text("判定：${it.status}", style = MaterialTheme.typography.titleMedium)
            Text(it.reason)
            if (it.evidence.isNotBlank()) Text(it.evidence)
        }
        OutlinedButton(onClick = onBack) { Text("戻る") }
    }
}
