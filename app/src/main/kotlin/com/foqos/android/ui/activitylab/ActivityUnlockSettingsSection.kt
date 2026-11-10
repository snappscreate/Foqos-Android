package com.foqos.android.ui.activitylab

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foqos.android.ui.profile.ProfileEditPending
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.repository.ProfileRepository

@Composable
fun ActivityUnlockSettingsSection(
    profileId: String, editingLocked: Boolean, registrationOnly: Boolean = false,
    profileRepository: ProfileRepository? = null,
    knownProfiles: List<BlockedProfile> = emptyList(),
    editor: ProfileEditPending? = null,
) {
    val context = LocalContext.current
    val libraryMode = registrationOnly
    val storageId = if (libraryMode) "__shared_catalog__" else profileId
    val store = remember(storageId) { ActivityUnlockStore(context) }
    val observedProfiles by (profileRepository?.observeAll()?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList<BlockedProfile>()) })
    val allProfiles = if (knownProfiles.isNotEmpty()) knownProfiles else observedProfiles
    var fallbackConditions by remember(storageId) { mutableStateOf(store.conditions(storageId)) }
    val conditions = if (editor != null) {
        if (libraryMode) editor.library else editor.activities
    } else fallbackConditions
    fun updateConditions(value: List<ActivityUnlockCondition>) {
        if (editor == null) fallbackConditions = value
        else if (libraryMode) editor.library = value
        else editor.activities = value
    }
    var editingId by remember(storageId, registrationOnly) { mutableStateOf<String?>(null) }
    var draft by remember(profileId) { mutableStateOf(ActivityUnlockCondition()) }
    var error by remember { mutableStateOf<String?>(null) }
    var chooseExisting by remember { mutableStateOf(false) }
    var registrationDialog by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var rewardRefresh by remember { mutableIntStateOf(0) }
    fun saveList(items: List<ActivityUnlockCondition>) {
        try {
            if (editor != null) {
                if (libraryMode) items.forEach(editor::editLibrary)
                else updateConditions(items)
            } else if (libraryMode) {
                items.forEach(store::updateLibraryCondition)
                updateConditions(store.conditions(storageId))
            } else {
                store.save(profileId, items)
                updateConditions(store.conditions(profileId))
            }
            error = null
        } catch (e: Exception) { error = e.message ?: "保存失敗" }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (registrationOnly) "動作の登録・編集を行う画面。休止時間はプロファイルの条件欄で設定するよ。" else
            "登録済みの動作をサブ条件へ追加するか、新しく動作を登録してね。休止時間はこのプロファイル専用の設定。",
            style = MaterialTheme.typography.bodySmall)
        conditions.filter { (registrationOnly && editingId == null) ||
            (!registrationOnly && it.id != (editor?.mainConditionId ?: store.mainConditionId(profileId))) }
            .forEach { condition ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Column(Modifier.weight(1f)) {
                    Text(condition.description)
                    Text("${if (condition.unlimitedRecording) "撮影時間は手動終了" else "撮影${condition.recordingSeconds}秒"} / 開始前${condition.countdownSeconds}秒 / ${if (condition.frontCamera) "前面" else "背面"}",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (registrationOnly) {
                    OutlinedButton(enabled = !editingLocked, onClick = {
                        draft = condition; editingId = condition.id
                    }) { Text("編集") }
                }
                OutlinedButton(enabled = !editingLocked, onClick = {
                    if (libraryMode) {
                        val owners = store.attachedProfileIds(condition.id).filterNot {
                            it == editor?.profileId && editor?.activities?.none { a -> a.id == condition.id } == true
                        }.toSet()
                        if (owners.isNotEmpty()) {
                            val names = allProfiles.filter { it.id in owners }.map { it.name }
                            error = if (owners.size == 1) {
                                "${names.firstOrNull() ?: "別のプロファイル"}で使用されています。削除できないよ"
                            } else "複数のプロファイルで使用されています。削除できないよ"
                        } else if (editor != null) {
                            editor.removeFromLibrary(condition.id)
                            error = null
                        } else if (store.deleteLibraryCondition(condition.id)) {
                            updateConditions(store.conditions(storageId))
                            error = null
                        } else error = "削除できなかったよ"
                    } else {
                        val inSets = (editor?.sets ?: ActivityChallengeSetStore(context).sets(profileId))
                            .any { condition.id in it.conditionIds }
                        if (inSets) {
                            error = "この動作は達成セットで使用されています。セットから外してから削除してね"
                        } else {
                            if (editor != null) editor.removeActivity(condition.id)
                            else saveList(conditions.filterNot { it.id == condition.id })
                        }
                    }
                    if (editingId == condition.id) editingId = null
                }) { Text("削除") }
            }
            if (!registrationOnly && editingId == null && condition.id != (editor?.mainConditionId ?: store.mainConditionId(profileId))) {
                val defaultMinutes = ProfileUnlockPolicyStore(context).read(profileId).pauseMinutes
                val rewardMinutes = remember(profileId, condition.id, rewardRefresh) {
                    editor?.activityRewards?.get(condition.id)
                        ?: store.conditionPauseMinutes(profileId, condition.id, defaultMinutes)
                }
                Text("このプロファイルのサブ条件として成功した場合：${rewardMinutes}分休止",
                    style = MaterialTheme.typography.bodySmall)
                Slider(value = rewardMinutes.toFloat(), enabled = !editingLocked,
                    onValueChange = { minutes ->
                        if (editor != null) editor.activityRewards =
                        editor.activityRewards + (condition.id to minutes.toInt())
                    else store.setConditionPauseMinutes(profileId, condition.id, minutes.toInt())
                    rewardRefresh++
                    }, valueRange = 1f..180f, steps = 178)
            }
            HorizontalDivider()
        }
        if (editingId == null) {
            if (registrationOnly) {
                OutlinedButton(enabled = !editingLocked, onClick = {
                    draft = ActivityUnlockCondition(); editingId = draft.id
                }) { Text("＋ 新しく対象動作を登録") }
            } else {
                Box {
                    OutlinedButton(enabled = !editingLocked, onClick = {
                        refresh++
                        chooseExisting = true
                    }) { Text("＋ 登録済みの動作を追加") }
                    DropdownMenu(expanded = chooseExisting, onDismissRequest = { chooseExisting = false }) {
                        (editor?.library ?: store.reusableConditions()).filter { candidate ->
                            conditions.none { it.id == candidate.id }
                        }.forEach { candidate ->
                            DropdownMenuItem(text = { Text(candidate.description) }, onClick = {
                                if (editor != null) editor.addActivity(candidate)
                                else {
                                    store.importCondition(profileId, candidate)
                                    updateConditions(store.conditions(profileId))
                                }
                                chooseExisting = false
                                refresh++
                            })
                        }
                    }
                }
                OutlinedButton(enabled = !editingLocked, onClick = { registrationDialog = true }) {
                    Text("＋ 動作判定を新しく登録")
                }
            }
        } else {
            Text("動作条件の編集", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = draft.description,
                onValueChange = { draft = draft.copy(description = it) },
                enabled = !editingLocked,
                label = { Text("判定する動作（例：腕立て伏せを30回行う）") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("撮影カメラ：")
                OutlinedButton(enabled = !editingLocked,
                    onClick = { draft = draft.copy(frontCamera = !draft.frontCamera) }) {
                    Text(if (draft.frontCamera) "前面（タップで背面）" else "背面（タップで前面）")
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("撮影時間の上限なし（手動終了）", modifier = Modifier.weight(1f))
                Switch(checked = draft.unlimitedRecording, enabled = !editingLocked,
                    onCheckedChange = { draft = draft.copy(unlimitedRecording = it) })
            }
            if (!draft.unlimitedRecording) Text("撮影時間：${draft.recordingSeconds}秒")
            if (!draft.unlimitedRecording) Slider(value = draft.recordingSeconds.toFloat(),
                onValueChange = { draft = draft.copy(recordingSeconds = it.toInt()) },
                valueRange = 10f..120f, steps = 10, enabled = !editingLocked)
            Text("カウントダウン：${draft.countdownSeconds}秒")
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0,3,5,10,15,20,30).forEach { seconds ->
                    OutlinedButton(enabled = !editingLocked, onClick = {
                        draft = draft.copy(countdownSeconds = seconds)
                    }) { Text(if (draft.countdownSeconds == seconds) "✓ ${seconds}秒" else "${seconds}秒") }
                }
            }
            Text("一時休止時間はプロファイルの一時休止条件で設定するよ。",
                style = MaterialTheme.typography.bodySmall)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = draft.description.isNotBlank(), onClick = {
                    context.startActivity(Intent(context, ActivityUnlockActivity::class.java).apply {
                        putExtra(ActivityUnlockActivity.EXTRA_TEST, true)
                        putExtra(ActivityUnlockActivity.EXTRA_DESCRIPTION, draft.description)
                        putExtra(ActivityUnlockActivity.EXTRA_SECONDS, draft.recordingSeconds)
                        putExtra(ActivityUnlockActivity.EXTRA_COUNTDOWN, draft.countdownSeconds)
                        putExtra(ActivityUnlockActivity.EXTRA_FRONT, draft.frontCamera)
                        putExtra(ActivityUnlockActivity.EXTRA_UNLIMITED, draft.unlimitedRecording)
                    })
                }) { Text("判定をテスト") }
                Button(enabled = !editingLocked && draft.description.isNotBlank(), onClick = {
                    val value = draft.normalized()
                    saveList(conditions.filterNot { it.id == value.id } + value)
                    editingId = null
                }) { Text("条件を保存") }
                OutlinedButton(onClick = { editingId = null }) { Text("戻る") }
            }
            Text("テストは判定結果を表示するだけで一時休止しない。", style = MaterialTheme.typography.bodySmall)
        }
        if (error != null) Text("保存エラー：$error", color = MaterialTheme.colorScheme.error)
    }
    if (registrationDialog && !registrationOnly) {
        Dialog(onDismissRequest = {
            registrationDialog = false
            updateConditions(store.conditions(profileId))
            refresh++
        }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxWidth().padding(12.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 12.dp,
                shadowElevation = 16.dp,
                border = androidx.compose.foundation.BorderStroke(
                    2.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("動作判定を新しく登録", style = MaterialTheme.typography.titleLarge)
                    ActivityUnlockSettingsSection(profileId, editingLocked, registrationOnly = true,
                        profileRepository = profileRepository, editor = editor)
                    TextButton(onClick = {
                        registrationDialog = false
                        updateConditions(store.conditions(profileId))
                        refresh++
                    }) { Text("閉じる") }
                }
            }
        }
    }
}

/** Manage all reusable movement definitions independently from any profile's reward sets. */
@Composable
fun MovementLibrarySection(
    allProfiles: List<BlockedProfile>,
    editingLocked: Boolean,
    editor: ProfileEditPending? = null,
) {
    ActivityUnlockSettingsSection(
        profileId = "__shared_catalog__",
        editingLocked = editingLocked,
        registrationOnly = true,
        knownProfiles = allProfiles,
        editor = editor,
    )
}
