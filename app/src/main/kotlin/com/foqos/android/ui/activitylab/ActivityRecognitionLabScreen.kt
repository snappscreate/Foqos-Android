@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.foqos.android.ui.activitylab

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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

    var activity by remember { mutableStateOf("腕立て伏せをする") }
    var mode by remember { mutableStateOf(ActivityMode.PHOTOS) }
    var front by remember { mutableStateOf(false) }
    var interval by remember { mutableStateOf(2000L) }
    var duration by remember { mutableStateOf(20) }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("待機中") }
    var busy by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var verdict by remember { mutableStateOf<ActivityVerdict?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    val latestFiles by rememberUpdatedState(files)
    val latestJob by rememberUpdatedState(captureJob)

    DisposableEffect(camera) {
        onDispose {
            latestJob?.cancel()
            camera.close()
            latestFiles.forEach { it.delete() }
        }
    }
    LaunchedEffect(granted, front, owner) {
        cameraReady = false
        if (granted) {
            try {
                camera.bind(owner, front)
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
            OutlinedTextField(
                value = activity, onValueChange = { activity = it },
                label = { Text("確認したい活動") },
                placeholder = { Text("例：腕立て伏せをする、本を読む") },
                enabled = !busy, modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActivityMode.entries.forEach { item ->
                    FilterChip(selected = mode == item, enabled = !busy,
                        onClick = { mode = item }, label = { Text(item.label) })
                }
            }
            if (granted) {
                AndroidView(factory = { camera.preview },
                    modifier = Modifier.fillMaxWidth().height(220.dp))
                OutlinedButton(enabled = !busy, onClick = { front = !front }) {
                    Text(if (front) "背面カメラに切り替え" else "前面カメラに切り替え")
                }
            } else {
                OutlinedButton(onClick = { requestCamera.launch(Manifest.permission.CAMERA) }) {
                    Text("カメラ使用を許可する")
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
            val photoCount = (duration * 1000L / interval).toInt().coerceIn(1, 40)
            if (mode != ActivityMode.VIDEO) {
                Text("取得予定：${photoCount}枚（最大40枚）",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (mode != ActivityMode.POSE) {
                Text("Geminiへ映像を送信するため、HTTPS中継サーバーが必要。キーはアプリ内に埋め込まない。",
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
                    label = { Text("認証トークン（必要な場合）") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (busy) {
                OutlinedButton(onClick = {
                    captureJob?.cancel()
                    status = "中止したよ。再度撮影できる。"
                    busy = false
                }) { Text("撮影・判定を中止") }
            } else {
                Button(
                    enabled = granted && cameraReady && activity.isNotBlank() &&
                        (mode == ActivityMode.POSE || endpoint.startsWith("https://")),
                    onClick = {
                        captureJob = scope.launch {
                            val previous = files
                            files = emptyList()
                            previous.forEach(File::delete)
                            verdict = null
                            busy = true
                            val started = SystemClock.elapsedRealtime()
                            val captures = mutableListOf<File>()
                            try {
                                val result = if (mode == ActivityMode.VIDEO) {
                                    status = "${duration}秒間の動画を撮影中..."
                                    val video = camera.recordFor(duration * 1000L)
                                    captures.add(video)
                                    files = captures.toList()
                                    status = "Geminiに動画を送信して判定中..."
                                    ActivityGeminiRelay.judge(
                                        endpoint.trim(), token.trim(), activity, "video",
                                        emptyList(), video, interval)
                                } else {
                                    val total = photoCount
                                    val actualInterval = (duration * 1000L / total).coerceAtLeast(interval)
                                    for (i in 0 until total) {
                                        status = "撮影中：${i + 1}/${total}枚"
                                        val frame = camera.captureFile()
                                        captures.add(frame)
                                        files = captures.toList()
                                        if (i < total - 1) delay(actualInterval)
                                    }
                                    status = "画像を分析中..."
                                    if (mode == ActivityMode.POSE) {
                                        PoseActivityJudge.judge(context, activity, captures)
                                    } else {
                                        ActivityGeminiRelay.judge(
                                            endpoint.trim(), token.trim(), activity, "photos",
                                            captures, null, actualInterval)
                                    }
                                }
                                verdict = result.copy(elapsedMs = SystemClock.elapsedRealtime() - started)
                                status = "判定完了"
                            } catch (cancel: CancellationException) {
                                status = "キャンセルしたよ"
                                throw cancel
                            } catch (error: Exception) {
                                status = "失敗：${error.message ?: error.javaClass.simpleName}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("撮影・判定を開始") }
            }

            val result = verdict
            if (result != null) {
                Text("判定：${result.status}", style = MaterialTheme.typography.titleLarge)
                Text(result.reason)
                if (result.evidence.isNotBlank()) Text(result.evidence)
                Text("処理時間：${result.elapsedMs / 1000.0}秒")
                if (result.inputTokens > 0 || result.outputTokens > 0) {
                    Text("入力：${result.inputTokens} tokens / 出力：${result.outputTokens} tokens")
                    result.estimatedYen?.let { Text("API推定費用：%.3f円".format(it)) }
                } else Text("端末内判定：API料金0円")
            }
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
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
