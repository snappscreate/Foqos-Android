package com.foqos.android.ui.activitylab

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun ActivityUnlockSettingsSection(profileId: String, editingLocked: Boolean) {
    val context = LocalContext.current
    val store = remember(profileId) { ActivityUnlockStore(context) }
    var conditions by remember(profileId) { mutableStateOf(store.conditions(profileId)) }
    var mainConditionId by remember(profileId) { mutableStateOf(store.mainConditionId(profileId)) }
    var editingId by remember(profileId) { mutableStateOf<String?>(null) }
    var draft by remember(profileId) { mutableStateOf(ActivityUnlockCondition()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun saveList(items: List<ActivityUnlockCondition>) {
        try { store.save(profileId, items); conditions = items; error = null }
        catch (e: Exception) { error = e.message ?: "保存失敗" }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("通常は上の解除方式がメイン。ここで登録した動作条件をメインに指定することもできる。", style = MaterialTheme.typography.bodySmall)
        if (mainConditionId != null) {
            OutlinedButton(enabled = !editingLocked, onClick = {
                store.setMainCondition(profileId, null)
                mainConditionId = null
            }) { Text("メインを通常の解除方式に戻す") }
        }
        conditions.forEach { condition ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(condition.description)
                    Text("撮影${condition.recordingSeconds}秒 / 開始前${condition.countdownSeconds}秒 / 一時停止${condition.pauseMinutes}分",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(enabled = !editingLocked, onClick = {
                    draft = condition; editingId = condition.id
                }) { Text("編集") }
                OutlinedButton(enabled = !editingLocked, onClick = {
                    store.setMainCondition(profileId, condition.id)
                    mainConditionId = condition.id
                }) { Text(if (mainConditionId == condition.id) "✓メイン" else "メインにする") }
                OutlinedButton(enabled = !editingLocked, onClick = {
                    saveList(conditions.filterNot { it.id == condition.id })
                    if (mainConditionId == condition.id) mainConditionId = null
                    if (editingId == condition.id) editingId = null
                }) { Text("削除") }
            }
            HorizontalDivider()
        }
        if (editingId == null) {
            OutlinedButton(enabled = !editingLocked, onClick = {
                draft = ActivityUnlockCondition(); editingId = draft.id
            }) { Text("＋ 動作条件を追加") }
        } else {
            Text("動作条件の編集", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = draft.description,
                onValueChange = { draft = draft.copy(description = it) },
                enabled = !editingLocked,
                label = { Text("判定する動作（例：腕立て伏せを30回行う）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("撮影時間：${draft.recordingSeconds}秒")
            Slider(value = draft.recordingSeconds.toFloat(),
                onValueChange = { draft = draft.copy(recordingSeconds = it.toInt()) },
                valueRange = 10f..60f, steps = 9, enabled = !editingLocked)
            Text("カウントダウン：${draft.countdownSeconds}秒")
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0,3,5,10,15,20,30).forEach { seconds ->
                    OutlinedButton(enabled = !editingLocked, onClick = {
                        draft = draft.copy(countdownSeconds = seconds)
                    }) { Text(if (draft.countdownSeconds == seconds) "✓ ${seconds}秒" else "${seconds}秒") }
                }
            }
            Text("成功時の一時停止時間：${draft.pauseMinutes}分")
            Slider(value = draft.pauseMinutes.toFloat(),
                onValueChange = { draft = draft.copy(pauseMinutes = it.toInt()) },
                valueRange = 1f..60f, steps = 58, enabled = !editingLocked)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = draft.description.isNotBlank(), onClick = {
                    context.startActivity(Intent(context, ActivityUnlockActivity::class.java).apply {
                        putExtra(ActivityUnlockActivity.EXTRA_TEST, true)
                        putExtra(ActivityUnlockActivity.EXTRA_DESCRIPTION, draft.description)
                        putExtra(ActivityUnlockActivity.EXTRA_SECONDS, draft.recordingSeconds)
                        putExtra(ActivityUnlockActivity.EXTRA_COUNTDOWN, draft.countdownSeconds)
                    })
                }) { Text("判定をテスト") }
                Button(enabled = !editingLocked && draft.description.isNotBlank(), onClick = {
                    val value = draft.normalized()
                    saveList(conditions.filterNot { it.id == value.id } + value)
                    editingId = null
                }) { Text("条件を保存") }
                OutlinedButton(onClick = { editingId = null }) { Text("戻る") }
            }
            Text("テストは判定結果を表示するだけで一時停止しない。", style = MaterialTheme.typography.bodySmall)
        }
        if (error != null) Text("保存エラー：$error", color = MaterialTheme.colorScheme.error)
    }
}
