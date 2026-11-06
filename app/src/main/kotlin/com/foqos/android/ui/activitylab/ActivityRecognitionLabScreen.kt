@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.foqos.android.ui.activitylab

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.ui.Alignment
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ActivityMode(val label: String) {
    PHOTOS("Gemini・静止画"),
    VIDEO("Gemini・動画"),
    POSE("端末内・姿勢判定"),
}

/**
 * Separate experimental screen. No path from this page can stop or pause a blocking session.
 * Temporary captures are erased on re-run and on navigation away.
 */
@Composable
fun ActivityRecognitionLabScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val camera = remember(context) { ActivityCamera(context) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED)
    }
    val requestCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    val settingsStore = remember(context) { ActivityLabSettingsStore(context) }
    val saved = remember(settingsStore) { settingsStore.load() }
    var activity by remember { mutableStateOf(saved.activity) }
    var mode by remember { mutableStateOf(ActivityMode.entries.firstOrNull { it.name == saved.mode } ?: ActivityMode.PHOTOS) }
    var front by remember { mutableStateOf(saved.frontCamera) }
    var interval by remember { mutableStateOf(saved.intervalMillis) }
    var duration by remember { mutableStateOf(saved.durationSeconds) }
    var useRelay by remember { mutableStateOf(saved.useRelay) }
    val keyStore = remember(context) { ActivityApiKeyStore(context) }
    var apiKey by remember { mutableStateOf(keyStore.load().orEmpty()) }
    var keySaved by remember { mutableStateOf(keyStore.hasKey()) }
    var keyMessage by remember { mutableStateOf("") }
    var geminiModel by remember { mutableStateOf(saved.geminiModel) }
    var endpoint by remember { mutableStateOf(saved.endpoint) }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("待機中") }
    var busy by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var showingResult by remember { mutableStateOf(false) }
    var capturedCount by remember { mutableStateOf(0) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var verdict by remember { mutableStateOf<ActivityVerdict?>(null) }
    var verdictMode by remember { mutableStateOf(ActivityMode.PHOTOS) }
    var cameraReady by remember { mutableStateOf(false) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    val photoCount = (duration * 1000L / interval).toInt().coerceIn(1, 40)
    LaunchedEffect(activity, mode, front, interval, duration, useRelay, geminiModel, endpoint) {
        settingsStore.save(ActivityLabSettingsStore.Settings(
            activity = activity, mode = mode.name, frontCamera = front,
            intervalMillis = interval, durationSeconds = duration,
            geminiModel = geminiModel, useRelay = useRelay, endpoint = endpoint,
        ))
    }
    val latestFiles by rememberUpdatedState(files)
    val latestJob by rememberUpdatedState(captureJob)

    DisposableEffect(camera) {
        // Remove temporary camera media left by an earlier force-close/crash.
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("activity_frame_") || it.name.startsWith("activity_video_")
        }?.forEach(File::delete)
        onDispose {
            latestJob?.cancel()
            camera.close()
            latestFiles.forEach { it.delete() }
        }
    }
    LaunchedEffect(granted, front, mode, owner) {
        cameraReady = false
        if (granted) {
            try {
                camera.bind(owner, front, mode == ActivityMode.VIDEO)
                cameraReady = true
            } catch (error: Exception) {
                status = "カメラを起動できない: ${error.message}"
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("AI活動認識テスト") },
            navigationIcon = { OutlinedButton(onClick = onBack) { Text("戻る") } })
    }) { inset ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ブロック解除には接続されない検証画面。撮影画像は終了時に削除する。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (granted) {
                Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                    AndroidView(factory = { camera.preview },
                        modifier = Modifier.fillMaxSize())
                    if (!busy) {
                        OutlinedButton(
                            onClick = { front = !front },
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                        ) {
                            Text(if (front) "背面に切替" else "前面に切替")
                        }
                    }
                }
            } else {
                OutlinedButton(onClick = { requestCamera.launch(Manifest.permission.CAMERA) }) {
                    Text("カメラ使用を許可する")
                }
            }
            if (!busy) {
                OutlinedTextField(
                    value = activity,
                    onValueChange = { activity = it },
                    label = { Text("確認したい活動") },
                    placeholder = { Text("例：腕立て伏せをする、本を読む") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            if (busy) {
                OutlinedButton(onClick = {
                    captureJob?.cancel()
                    status = "中止したよ。再度撮影できる。"
                    busy = false
                }) { Text("撮影・判定を中止") }
            } else {
                Button(
                    enabled = granted && cameraReady && activity.isNotBlank() &&
                        (mode == ActivityMode.POSE || if (useRelay) endpoint.startsWith("https://") else apiKey.isNotBlank()),
                    onClick = {
                        captureJob = scope.launch {
                            val previous = files
                            files = emptyList()
                            previous.forEach(File::delete)
                            verdict = null
                            showingResult = false
                            capturedCount = 0
                            busy = true
                            var analysisStarted = SystemClock.elapsedRealtime()
                            val captures = mutableListOf<File>()
                            try {
                                val result = if (mode == ActivityMode.VIDEO) {
                                    status = "${duration}秒間の動画を撮影中..."
                                    val video = camera.recordFor(duration * 1000L)
                                    captures.add(video)
                                    capturedCount = 1
                                    files = captures.toList()
                                    status = "Geminiに動画を送信して判定中..."
                                    analysisStarted = SystemClock.elapsedRealtime()
                                    if (useRelay) ActivityGeminiRelay.judge(
                                        endpoint.trim(), token.trim(), geminiModel, activity, "video",
                                        emptyList(), video, interval)
                                    else ActivityGeminiRelay.judgeDirect(
                                        apiKey.trim(), geminiModel, activity, emptyList(), video, interval)
                                } else {
                                    val total = photoCount
                                    val actualInterval = (duration * 1000L / total).coerceAtLeast(interval)
                                    for (i in 0 until total) {
                                        status = "撮影中：${i + 1}/${total}枚"
                                        val frame = camera.captureFile()
                                        captures.add(frame)
                                        capturedCount = captures.size
                                        files = captures.toList()
                                        if (i < total - 1) delay(actualInterval)
                                    }
                                    status = "画像を分析中..."
                                    analysisStarted = SystemClock.elapsedRealtime()
                                    if (mode == ActivityMode.POSE) {
                                        PoseActivityJudge.judge(context, activity, captures)
                                    } else {
                                        if (useRelay) ActivityGeminiRelay.judge(
                                            endpoint.trim(), token.trim(), geminiModel, activity, "photos",
                                            captures, null, actualInterval)
                                        else ActivityGeminiRelay.judgeDirect(
                                            apiKey.trim(), geminiModel, activity, captures, null, actualInterval)
                                    }
                                }
                                verdictMode = mode
                                verdict = result.copy(elapsedMs = SystemClock.elapsedRealtime() - analysisStarted)
                                status = "待機中"
                                showingResult = true
                            } catch (cancel: CancellationException) {
                                status = "キャンセルしたよ"
                                throw cancel
                            } catch (error: Exception) {
                                val description = error.message ?: error.javaClass.simpleName
                                verdictMode = mode
                                verdict = ActivityVerdict("エラー", description)
                                status = "待機中"
                                showingResult = true
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("撮影・判定を開始") }
            }

            if (!busy) {
                Text("活動：$activity", style = MaterialTheme.typography.bodyMedium)
                Text("方式：${mode.label} / 観察時間：${duration}秒" +
                    (if (mode == ActivityMode.VIDEO) "" else " / 撮影間隔：${interval / 1000.0}秒"),
                    style = MaterialTheme.typography.bodySmall)
                Text("カメラ：${if (front) "前面" else "背面"}" +
                    (if (mode == ActivityMode.POSE) ""
                    else " / モデル：${if (geminiModel.endsWith("-lite")) "3.5 Flash-Lite" else "3.5 Flash"}") +
                    (if (mode == ActivityMode.POSE || useRelay) ""
                    else " / APIキー：${if (apiKey.isNotBlank()) "設定済み" else "未設定"}"),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { settingsExpanded = !settingsExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (settingsExpanded) "▲ 設定を閉じる" else "▼ 設定を変更")
                }
                if (settingsExpanded) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityMode.entries.forEach { item ->
                    FilterChip(selected = mode == item, enabled = !busy,
                        onClick = { mode = item }, label = { Text(item.label) })
                }
            }
            if (mode != ActivityMode.VIDEO) {
                Text("撮影間隔：${interval / 1000.0}秒")
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(500L, 1000L, 2000L, 5000L).forEach { value ->
                        FilterChip(selected = interval == value, enabled = !busy,
                            onClick = { interval = value },
                            label = { Text("${value / 1000.0}秒") })
                    }
                }
            }
            Text("観察時間：${duration}秒")
            Slider(
                value = duration.toFloat(), onValueChange = { duration = it.toInt() },
                valueRange = 10f..60f, steps = 9, enabled = !busy,
            )

            if (mode != ActivityMode.VIDEO) {
                val effectiveInterval = (duration * 1000L / photoCount).coerceAtLeast(interval)
                Text("取得予定：${photoCount}枚（最大40枚）、実際の間隔：約${effectiveInterval / 1000.0}秒",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (mode != ActivityMode.POSE) {
                Text("AIモデル")
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("gemini-3.5-flash", "gemini-3.5-flash-lite").forEach { model ->
                        FilterChip(selected = geminiModel == model, enabled = !busy,
                            onClick = { geminiModel = model },
                            label = { Text(if (model.endsWith("-lite")) "3.5 Flash-Lite（低コスト）"
                                else "3.5 Flash") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !useRelay, enabled = !busy,
                        onClick = { useRelay = false },
                        label = { Text("直接API（テスト専用）") })
                    FilterChip(selected = useRelay, enabled = !busy,
                        onClick = { useRelay = true },
                        label = { Text("HTTPS中継") })
                }
                if (useRelay) {
                    Text("中継側にGeminiのAPIキーを置く方式。通常運用ではこちらを推奨。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = endpoint, onValueChange = { endpoint = it },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy,
                        label = { Text("中継サーバーURL（HTTPS）") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = token, onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy,
                        label = { Text("中継サーバー認証トークン") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                } else {
                    Text("個人用のテスト向け。APIキーは端末内で暗号化保存できる。映像はGeminiへ送信されるので、公開アプリでは中継方式を使ってね。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = apiKey, onValueChange = {
                            apiKey = it
                            keyMessage = ""
                        },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy,
                        label = { Text("Gemini APIキー") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy && apiKey.isNotBlank(), onClick = {
                            runCatching { keyStore.save(apiKey) }
                                .onSuccess {
                                    keySaved = true
                                    keyMessage = "APIキーを暗号化して保存したよ"
                                }.onFailure {
                                    keyMessage = "保存に失敗：${it.message}"
                                }
                        }) { Text("APIキーを保存") }
                        OutlinedButton(enabled = !busy && keySaved, onClick = {
                            keyStore.delete()
                            apiKey = ""
                            keySaved = false
                            keyMessage = "保存したAPIキーを削除したよ"
                        }) { Text("保存キーを削除") }
                    }
                    if (keyMessage.isNotBlank()) Text(keyMessage,
                        style = MaterialTheme.typography.bodySmall)
                    if (keySaved) Text("この端末に保存済み（Android Keystore暗号化）",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
                }
            } else {
                Text("撮影・判定中", style = MaterialTheme.typography.titleMedium)
                Text("取得データ：${capturedCount}件")
            }
            if (busy) {
                Text(status, style = MaterialTheme.typography.bodyMedium)
            if (files.isNotEmpty()) {
                Text("取得したデータ：${files.size}件",
                    style = MaterialTheme.typography.titleSmall)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    files.filter { it.extension == "jpg" }.forEach { imageFile ->
                        val thumbnail = remember(imageFile) {
                            BitmapFactory.decodeFile(imageFile.absolutePath,
                                BitmapFactory.Options().apply { inSampleSize = 8 })
                                ?.asImageBitmap()
                        }
                        if (thumbnail != null) Image(bitmap = thumbnail,
                            contentDescription = "撮影画像",
                            modifier = Modifier.size(64.dp))
                    }
                }
            }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    val result = verdict
    if (showingResult && result != null) {
        AlertDialog(
            onDismissRequest = { showingResult = false },
            title = { Text("活動判定：${result.status}") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(result.reason)
                    if (result.evidence.isNotBlank()) Text(result.evidence)
                    Text("判定処理時間：${result.elapsedMs / 1000.0}秒")
                    if (verdictMode != ActivityMode.POSE) {
                        Text("入力：${result.inputTokens} tokens / 出力：${result.outputTokens} tokens")
                        result.estimatedYen?.let { Text("推定費用：%.3f円".format(it)) }
                    } else Text("端末内判定：API料金0円")
                }
            },
            confirmButton = {
                TextButton(onClick = { showingResult = false }) { Text("閉じる") }
            },
        )
    }

}
