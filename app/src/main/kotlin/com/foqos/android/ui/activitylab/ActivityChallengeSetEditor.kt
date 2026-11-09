package com.foqos.android.ui.activitylab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.repository.ProfileRepository
import java.util.UUID

/** Each row is AND; rows are OR. Rewards are owned by the profile, not the movement. */
@Composable
fun ActivityChallengeSetEditor(
    profileId: String,
    editingLocked: Boolean,
    repository: ProfileRepository,
) {
    val context = LocalContext.current
    val store = remember(profileId) { ActivityChallengeSetStore(context) }
    val activities = remember(profileId) { ActivityUnlockStore(context) }
    var sets by remember(profileId) { mutableStateOf(store.sets(profileId)) }
    var changeToken by remember { mutableIntStateOf(0) }
    var libraryDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val allProfiles by repository.observeAll().collectAsState(initial = emptyList())
    val catalog = remember(libraryDialog, changeToken) { activities.reusableConditions() }
    fun persist(value: List<ActivityChallengeSet>) {
        try {
            // Never save a set with nonexistent reusable activity IDs.
            require(value.all { group -> group.conditionIds.all { id ->
                catalog.any { it.id == id }
            } }) { "保存されていない動作が含まれているよ" }
            value.flatMap { it.conditionIds }.distinct().forEach { id ->
                catalog.firstOrNull { it.id == id }?.let {
                    activities.importCondition(profileId, it)
                }
            }
            store.save(profileId, value)
            sets = value
            error = null
        } catch (e: Exception) { error = e.message ?: "セットを保存できなかった" }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("同じセット内は全て達成（AND）。別々のセットはどれか1つ（OR）。",
            style = MaterialTheme.typography.bodySmall)
        Text("セットを登録すると、単独メイン・サブ動作の休止時間よりセット単位の休止時間を優先するよ。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sets.isEmpty()) Text("動作セットは未設定。従来の単独メイン・サブ条件で判定するよ。",
            style = MaterialTheme.typography.bodySmall)
        sets.forEachIndexed { index, group ->
            if (index > 0) {
                HorizontalDivider()
                Text("または（OR）", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("セット ${index + 1}", style = MaterialTheme.typography.titleMedium)
                        TextButton(enabled = !editingLocked,
                            onClick = { persist(sets.filterNot { it.id == group.id }) }) {
                            Text("セット削除")
                        }
                    }
                    group.conditionIds.forEachIndexed { motionIndex, conditionId ->
                        if (motionIndex > 0) Text("＋ かつ（AND）",
                            style = MaterialTheme.typography.labelMedium)
                        var expanded by remember(group.id, motionIndex) { mutableStateOf(false) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !editingLocked,
                                    onClick = { expanded = true },
                                ) {
                                    Text(catalog.firstOrNull { it.id == conditionId }?.description ?: "動作を選択")
                                }
                                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                    catalog.filter { it.id == conditionId || it.id !in group.conditionIds }
                                        .forEach { option ->
                                            DropdownMenuItem(text = { Text(option.description) }, onClick = {
                                                persist(sets.map {
                                                    if (it.id != group.id) it else it.copy(
                                                        conditionIds = it.conditionIds.toMutableList().also { ids ->
                                                            ids[motionIndex] = option.id
                                                        })
                                                })
                                                expanded = false
                                            })
                                        }
                                }
                            }
                            if (group.conditionIds.size > 1) {
                                TextButton(enabled = !editingLocked, onClick = {
                                    persist(sets.map {
                                        if (it.id != group.id) it else it.copy(
                                            conditionIds = it.conditionIds.filterIndexed { i, _ -> i != motionIndex })
                                    })
                                }) { Text("削除") }
                            }
                        }
                    }
                    OutlinedButton(enabled = !editingLocked &&
                        group.conditionIds.size < catalog.size, onClick = {
                        val next = catalog.firstOrNull { it.id !in group.conditionIds }
                        if (next != null) persist(sets.map {
                            if (it.id != group.id) it else it.copy(
                                conditionIds = it.conditionIds + next.id)
                        })
                    }) { Text("＋ このセットに動作を追加（AND）") }
                    Text("全て達成した場合：${group.pauseMinutes}分休止",
                        style = MaterialTheme.typography.titleSmall)
                    Slider(value = group.pauseMinutes.toFloat(), onValueChange = { minutes ->
                        persist(sets.map {
                            if (it.id != group.id) it else it.copy(pauseMinutes = minutes.toInt())
                        })
                    }, enabled = !editingLocked, valueRange = 1f..180f, steps = 178)
                }
            }
        }
        OutlinedButton(enabled = !editingLocked && catalog.isNotEmpty(), onClick = {
            val id = catalog.first().id
            persist(sets + ActivityChallengeSet(
                id = UUID.randomUUID().toString(), conditionIds = listOf(id), pauseMinutes = 10))
        }, modifier = Modifier.fillMaxWidth()) { Text("＋ 別の達成セットを追加（OR）") }
        OutlinedButton(enabled = !editingLocked, onClick = { libraryDialog = true },
            modifier = Modifier.fillMaxWidth()) { Text("保存済みの動作を管理・新しく登録") }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
    }
    if (libraryDialog) {
        Dialog(onDismissRequest = {
            libraryDialog = false
            changeToken++
        }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().padding(12.dp),
                tonalElevation = 12.dp, shadowElevation = 16.dp,
                shape = MaterialTheme.shapes.large,
                border = androidx.compose.foundation.BorderStroke(
                    2.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    MovementLibrarySection(allProfiles, editingLocked)
                    TextButton(onClick = { libraryDialog = false; changeToken++ }) {
                        Text("閉じる")
                    }
                }
            }
        }
    }
}
