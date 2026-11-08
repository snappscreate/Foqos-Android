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
import com.foqos.android.data.model.PhysicalUnblockItem
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.service.location.LocationAutomationStore
import com.foqos.android.service.nfc.NfcForegroundDispatchManager
import com.foqos.android.ui.physicalunlock.PhysicalUnlockEditor
import com.foqos.android.ui.settings.LocationAutomationSection
import com.foqos.android.ui.settings.LocationAutomationViewModel
import kotlinx.coroutines.launch

/** A single location toggle, and independent stop / pause authentication selectors. */
@Composable
fun ProfileUnlockPolicySection(
    profile: BlockedProfile,
    editingLocked: Boolean,
    isPersisted: Boolean,
    profileRepository: ProfileRepository,
    nfcDispatchManager: NfcForegroundDispatchManager,
    locationViewModel: LocationAutomationViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val policies = remember(profile.id) { ProfileUnlockPolicyStore(context) }
    val activityStore = remember(profile.id) { ActivityUnlockStore(context) }
    val locationStore = remember(profile.id) { LocationAutomationStore(context) }
    val locationState by remember(profile.id) { locationStore.observeState(profile.id) }
        .collectAsState(initial = null to null)
    val activeArea = locationState.first != null && locationStore.enabled(profile.id)
    val current by profileRepository.observeById(profile.id).collectAsState(initial = null)
    val allProfiles by profileRepository.observeAll().collectAsState(initial = emptyList())
    val currentItems = current?.physicalUnblockItems.orEmpty()
    val catalog = remember { ReusablePhysicalCatalog(context) }
    // Import all existing registrations (including old profiles) without deleting
    // the reusable library when an owner profile is removed.
    val profileItems = allProfiles.flatMap { it.physicalUnblockItems } + currentItems
    LaunchedEffect(profileItems) { catalog.remember(profileItems) }
    val reusableItems = (catalog.items() + profileItems).distinctBy { it.id }
    var policy by remember(profile.id) { mutableStateOf(policies.forProfile(profile)) }
    var areaDialog by remember { mutableStateOf(false) }
    var closeAreaOnSave by remember { mutableStateOf(false) }
    var credentialsDialog by remember { mutableStateOf(false) }
    var activityDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(locationState.first, locationStore.enabled(profile.id), closeAreaOnSave) {
        if (closeAreaOnSave && locationState.first != null && locationStore.enabled(profile.id)) {
            areaDialog = false
            closeAreaOnSave = false
        }
    }
    val reusableActivities = remember(profile.id, refresh, activityDialog) {
        activityStore.reusableConditions()
    }
    val existingActivities = activityStore.conditions(profile.id)
    val mainId = activityStore.mainConditionId(profile.id)

    fun savePolicy(next: ProfileUnlockPolicy) {
        try {
            policies.save(profile.id, next)
            policy = next
            error = null
        } catch (e: Exception) { error = e.message ?: "保存に失敗" }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("ブロック範囲を使用", style = MaterialTheme.typography.titleSmall)
                Text("範囲外で自動的に一時休止し、手動で完全停止も可能。完全停止後は再入域しても再開しない。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = activeArea,
                enabled = !editingLocked && isPersisted,
                onCheckedChange = { enabled ->
                    if (enabled && locationState.first == null) {
                        closeAreaOnSave = true
                        areaDialog = true
                    } else {
                        locationViewModel.setAutomationEnabled(profile.id, enabled)
                    }
                },
            )
        }
        if (!isPersisted) Text("位置範囲やタグを設定するには、まずプロファイルを保存してね",
            style = MaterialTheme.typography.bodySmall)
        if (locationState.first != null) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("登録済み：半径 ${locationState.first!!.radiusMeters.toInt()}m",
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !editingLocked, onClick = {
                    closeAreaOnSave = false
                    areaDialog = true
                }) { Text("範囲を編集") }
            }
        }
        if (locationViewModel.status.isNotEmpty()) {
            Text(locationViewModel.status, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text("停止条件", style = MaterialTheme.typography.titleMedium)
        UnlockDropdown(policy.stop, listOf(
            UnlockMethod.MANUAL to "手動",
            UnlockMethod.NFC to "NFCタグ",
            UnlockMethod.QR to "QRコード",
        ), !editingLocked, onSelected = {
            savePolicy(policy.copy(stop = it, stopCredentialId = if (it == policy.stop) policy.stopCredentialId else null))
        })
        if (policy.stop == UnlockMethod.NFC || policy.stop == UnlockMethod.QR) {
            val kind = if (policy.stop == UnlockMethod.NFC) PhysicalUnblockType.NFC else PhysicalUnblockType.QR_CODE
            CredentialDropdown(
                title = if (kind == PhysicalUnblockType.NFC) "停止に使用するNFCタグ" else "停止に使用するQRコード",
                items = reusableItems.filter { it.type == kind }, selectedId = policy.stopCredentialId,
                enabled = !editingLocked,
                onSelect = { chosen ->
                    scope.launch {
                        profileRepository.updatePhysicalUnblockItems(profile.id) { old ->
                            if (old.any { it.id == chosen.id }) old else old + chosen
                        }
                        savePolicy(policy.copy(stopCredentialId = chosen.id))
                    }
                },
                onAdd = { credentialsDialog = true },
            )
        }
        HorizontalDivider()
        Text("一時休止条件（メイン）", style = MaterialTheme.typography.titleMedium)
        UnlockDropdown(policy.pause, listOf(
            UnlockMethod.MANUAL to "手動",
            UnlockMethod.NFC to "NFCタグ",
            UnlockMethod.QR to "QRコード",
            UnlockMethod.ACTIVITY to "AI動作判定",
        ), !editingLocked, onSelected = {
            savePolicy(policy.copy(pause = it, pauseCredentialId = if (it == policy.pause) policy.pauseCredentialId else null))
        })
        when (policy.pause) {
            UnlockMethod.NFC, UnlockMethod.QR -> {
                val kind = if (policy.pause == UnlockMethod.NFC) PhysicalUnblockType.NFC else PhysicalUnblockType.QR_CODE
                CredentialDropdown(
                    title = if (kind == PhysicalUnblockType.NFC) "一時休止に使用するNFCタグ" else "一時休止に使用するQRコード",
                    items = reusableItems.filter { it.type == kind }, selectedId = policy.pauseCredentialId,
                    enabled = !editingLocked,
                    onSelect = { chosen ->
                        scope.launch {
                            profileRepository.updatePhysicalUnblockItems(profile.id) { old ->
                                if (old.any { it.id == chosen.id }) old else old + chosen
                            }
                            savePolicy(policy.copy(pauseCredentialId = chosen.id))
                        }
                    },
                    onAdd = { credentialsDialog = true },
                )
            }
            UnlockMethod.ACTIVITY -> {
                Text("一時休止に使用する動作判定", style = MaterialTheme.typography.bodySmall)
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(enabled = !editingLocked, onClick = {
                        refresh++
                        expanded = true
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(existingActivities.firstOrNull { it.id == mainId }?.description ?: "登録済みの動作を選択")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        reusableActivities.forEach { candidate ->
                            DropdownMenuItem(text = { Text(candidate.description) }, onClick = {
                                activityStore.importCondition(profile.id, candidate)
                                activityStore.setMainCondition(profile.id, candidate.id)
                                refresh++
                                expanded = false
                            })
                        }
                    }
                }
                if (mainId == null || existingActivities.none { it.id == mainId }) {
                    Text("動作が未登録。選択するまでブロックを開始できないよ",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(enabled = !editingLocked, onClick = { activityDialog = true }) {
                    Text("＋ 動作判定を新しく登録")
                }
            }
            else -> Unit
        }
        if (policy.pause != UnlockMethod.ACTIVITY) {
            Text("一時休止時間：${policy.pauseMinutes}分")
            Slider(value = policy.pauseMinutes.toFloat(),
                onValueChange = { savePolicy(policy.copy(pauseMinutes = it.toInt())) },
                valueRange = 1f..120f, steps = 118, enabled = !editingLocked)
        }
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
    }

    if (areaDialog) {
        Dialog(onDismissRequest = { areaDialog = false; closeAreaOnSave = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ブロック範囲の設定", style = MaterialTheme.typography.titleLarge)
                    LocationAutomationSection(profile.id, editingLocked, isPersisted, locationViewModel)
                    TextButton(onClick = { areaDialog = false; closeAreaOnSave = false }) { Text("閉じる") }
                }
            }
        }
    }

    if (credentialsDialog) {
        Dialog(onDismissRequest = { credentialsDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text("NFC・QRコードを登録", style = MaterialTheme.typography.titleLarge)
                    PhysicalUnlockEditor(profile.id, profileRepository, nfcDispatchManager,
                        editingLocked = editingLocked)
                    Text("登録したらここを閉じ、条件の選択欄からタグ・コードを選んでね",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { credentialsDialog = false }) { Text("閉じる") }
                }
            }
        }
    }
    if (activityDialog) {
        Dialog(onDismissRequest = { activityDialog = false; refresh++ },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    Text("動作判定を登録", style = MaterialTheme.typography.titleLarge)
                    ActivityUnlockSettingsSection(profile.id, editingLocked)
                    TextButton(onClick = { activityDialog = false; refresh++ }) { Text("閉じる") }
                }
            }
        }
    }
}

@Composable
private fun UnlockDropdown(
    value: UnlockMethod, options: List<Pair<UnlockMethod, String>>,
    enabled: Boolean, onSelected: (UnlockMethod) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(options.firstOrNull { it.first == value }?.second ?: "選択")
            Spacer(Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (method, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    onSelected(method)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun CredentialDropdown(
    title: String, items: List<PhysicalUnblockItem>, selectedId: String?,
    enabled: Boolean, onSelect: (PhysicalUnblockItem) -> Unit, onAdd: () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.bodySmall)
    var expanded by remember { mutableStateOf(false) }
    val selected = items.firstOrNull { it.id == selectedId }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.name ?: "登録済みから選択")
            Spacer(Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(text = { Text(item.name) }, onClick = {
                    onSelect(item)
                    expanded = false
                })
            }
            if (items.isEmpty()) {
                DropdownMenuItem(text = { Text("まだ登録されていないよ") }, enabled = false, onClick = {})
            }
        }
    }
    if (selected == null) {
        Text("この条件のタグ・コードが未設定。選択するまでブロックを開始できないよ",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    OutlinedButton(onClick = onAdd, enabled = enabled) { Text("＋ 新しく登録") }
}
