package com.foqos.android.ui.activitylab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foqos.android.ui.profile.ProfileEditPending
import com.foqos.android.ui.common.DurationSliderField
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.repository.ProfileRepository
import java.util.UUID

/** Each row is AND; rows are OR. Rewards are owned by the profile, not the movement. */
@Composable
fun ActivityChallengeSetEditor(
    profileId: String,
    editingLocked: Boolean,
    repository: ProfileRepository,
    editor: ProfileEditPending,
    subCondition: Boolean = false,
) {
    val context = LocalContext.current
    val store = remember(profileId) { ActivityChallengeSetStore(context) }
    val activities = remember(profileId) { ActivityUnlockStore(context) }
    val sets = editor.sets + editor.subSets
    var changeToken by remember { mutableIntStateOf(0) }
    var libraryDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val allProfiles by repository.observeAll().collectAsState(initial = emptyList())
    val catalog = editor.library
    fun persist(value: List<ActivityChallengeSet>) {
        try {
            // Never save a set with nonexistent reusable activity IDs.
            require(value.all { group -> group.conditionIds.all { id ->
                catalog.any { it.id == id }
            } }) { "保存されていない動作が含まれているよ" }
            value.flatMap { it.conditionIds }.distinct().forEach { id ->
                catalog.firstOrNull { it.id == id }?.let { editor.addActivity(it) }
            }
            editor.sets = value
            editor.subSets = emptyList()
            error = null
        } catch (e: Exception) { error = e.message ?: "セットを保存できなかった" }
    }

    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("同じセット内は全て達成（AND）。別々のセットはどれか1つ（OR）。",
            style = MaterialTheme.typography.bodySmall)
        if (sets.isEmpty()) Text("達成セットが未登録。下のボタンから追加してね。",
            style = MaterialTheme.typography.bodySmall)
        sets.forEachIndexed { index, group ->
            if (index > 0) {
                HorizontalDivider()
                Text("または（OR）", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                                        },
                                                        targets = it.targets - conditionId +
                                                            (option.id to (it.targets[option.id] ?: ActivityTarget.inferred(option))))
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
                                            conditionIds = it.conditionIds.filterIndexed { i, _ -> i != motionIndex },
                                            targets = it.targets - conditionId)
                                    })
                                }) { Text("削除") }
                            }
                        }
                        val target = group.targets[conditionId] ?:
                            ActivityTarget.inferred(catalog.firstOrNull { it.id == conditionId })
                        val maxAmount = if (target.kind == ActivityTargetKind.COUNT) 300 else 180
                        var amountText by remember(group.id, conditionId) {
                            mutableStateOf(target.amount.toString())
                        }
                        var amountFocused by remember(group.id, conditionId) { mutableStateOf(false) }
                        LaunchedEffect(target.amount, amountFocused) {
                            if (!amountFocused) amountText = target.amount.toString()
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            listOf(ActivityTargetKind.COUNT to "回数", ActivityTargetKind.MINUTES to "時間")
                                .forEach { (kind, caption) ->
                                    FilterChip(
                                        selected = target.kind == kind,
                                        enabled = !editingLocked,
                                        onClick = {
                                            persist(sets.map { entry ->
                                                if (entry.id != group.id) entry else entry.copy(
                                                    targets = entry.targets + (conditionId to target.copy(kind = kind)))
                                            })
                                        },
                                        label = { Text(caption) },
                                    )
                                }
                            Spacer(Modifier.weight(1f))
                            OutlinedTextField(
                                value = amountText,
                                onValueChange = { input ->
                                    if (input.length <= 3 && input.all(Char::isDigit)) {
                                        amountText = input
                                        input.toIntOrNull()?.takeIf { it in 1..maxAmount }?.let { amount ->
                                            persist(sets.map { entry ->
                                                if (entry.id != group.id) entry else entry.copy(
                                                    targets = entry.targets + (conditionId to target.copy(amount = amount)))
                                            })
                                        }
                                    }
                                },
                                modifier = Modifier.width(100.dp).onFocusChanged { state ->
                                    val wasFocused = amountFocused
                                    amountFocused = state.isFocused
                                    if (wasFocused && !state.isFocused) {
                                        amountText = target.amount.toString()
                                    }
                                },
                                enabled = !editingLocked,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                suffix = { Text(if (target.kind == ActivityTargetKind.COUNT) "回" else "分") },
                            )
                        }
                    }
                    OutlinedButton(enabled = !editingLocked &&
                        group.conditionIds.size < catalog.size, onClick = {
                        val next = catalog.firstOrNull { it.id !in group.conditionIds }
                        if (next != null) persist(sets.map {
                            if (it.id != group.id) it else it.copy(
                                conditionIds = it.conditionIds + next.id,
                                targets = it.targets + (next.id to ActivityTarget.inferred(next)))
                        })
                    }) { Text("＋ このセットに動作を追加（AND）") }
                    DurationSliderField(
                        label = "全て達成した場合の休止時間",
                        value = group.pauseMinutes,
                        onValueChange = { minutes ->
                            persist(sets.map {
                                if (it.id != group.id) it else it.copy(pauseMinutes = minutes)
                            })
                        },
                        range = 1..180, enabled = !editingLocked,
                    )
                }
            }
        }
        OutlinedButton(enabled = !editingLocked && catalog.isNotEmpty(), onClick = {
            val id = catalog.first().id
            persist(sets + ActivityChallengeSet(
                id = UUID.randomUUID().toString(), conditionIds = listOf(id), pauseMinutes = 10,
                targets = mapOf(id to ActivityTarget.inferred(catalog.first()))))
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
                    MovementLibrarySection(allProfiles, editingLocked, editor)
                    TextButton(onClick = { libraryDialog = false; changeToken++ }) {
                        Text("閉じる")
                    }
                }
            }
        }
    }
}
