package com.foqos.android.ui.activitylab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.service.location.LocationAutomationStore

/** Stops and pauses are independent. Starting is always a home action. */
@Composable
fun ProfileUnlockPolicySection(profile: BlockedProfile, editingLocked: Boolean) {
    val context = LocalContext.current
    val store = remember(profile.id) { ProfileUnlockPolicyStore(context) }
    val activityStore = remember(profile.id) { ActivityUnlockStore(context) }
    var policy by remember(profile.id) { mutableStateOf(store.forProfile(profile)) }
    var mainActivity by remember(profile.id) { mutableStateOf(activityStore.mainConditionId(profile.id)) }
    val hasLocation = remember(profile.id) { LocationAutomationStore(context).read(profile.id) != null }
    var error by remember { mutableStateOf<String?>(null) }

    fun update(next: ProfileUnlockPolicy) {
        try {
            store.save(profile.id, next)
            policy = next
            error = null
        } catch (e: Exception) { error = e.message }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("停止条件", style = MaterialTheme.typography.titleMedium)
        Text("停止はセッションを終了。一時停止とは別に設定する。", style = MaterialTheme.typography.bodySmall)
        listOf(
            UnlockMethod.MANUAL to (if (hasLocation) "ブロック範囲から出る" else "手動"),
            UnlockMethod.NFC to "NFC",
            UnlockMethod.QR to "QRコード",
        ).forEach { (method, label) ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = policy.stop == method,
                    enabled = !editingLocked,
                    onClick = { update(policy.copy(stop = method)) })
                Text(label)
            }
        }
        if (hasLocation) Text("範囲外では自動的にブロックを休止し、戻ると再開する。完全停止とは異なる。",
            style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("一時停止条件（メイン）", style = MaterialTheme.typography.titleMedium)
        listOf(
            UnlockMethod.MANUAL to "手動",
            UnlockMethod.NFC to "NFC",
            UnlockMethod.QR to "QRコード",
            UnlockMethod.ACTIVITY to "動作判定",
        ).forEach { (method, label) ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = policy.pause == method,
                    enabled = !editingLocked,
                    onClick = { update(policy.copy(pause = method)) })
                Text(label)
            }
        }
        if (policy.pause == UnlockMethod.ACTIVITY) {
            val activities = activityStore.conditions(profile.id)
            Text("メインの動作判定", style = MaterialTheme.typography.titleSmall)
            if (activities.isEmpty()) {
                Text("下の「一時停止のサブ条件」で動作を登録してから選んでね",
                    color = MaterialTheme.colorScheme.error)
            } else {
                activities.forEach { condition ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(
                            selected = mainActivity == condition.id,
                            enabled = !editingLocked,
                            onClick = {
                                activityStore.setMainCondition(profile.id, condition.id)
                                mainActivity = condition.id
                            },
                        )
                        Text(condition.description)
                    }
                }
            }
        } else {
            Text("一時停止時間：${policy.pauseMinutes}分")
            Slider(
                value = policy.pauseMinutes.toFloat(),
                onValueChange = { update(policy.copy(pauseMinutes = it.toInt())) },
                valueRange = 1f..120f, steps = 118,
                enabled = !editingLocked,
            )
        }
        Text("NFC・QRを使う場合は、下の「NFC・QRコードによる解除」でタグやコードを登録してね。",
            style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
