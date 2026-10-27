package com.foqos.android.ui.physicalunlock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foqos.android.data.model.PhysicalUnblockItem
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.service.nfc.NfcForegroundDispatchManager
import com.foqos.android.ui.qr.QrScannerScreen
import com.foqos.android.ui.theme.FoqosTheme
import java.time.Instant
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class ScanMode { NFC, QR }

/**
 * Manages a profile's [PhysicalUnblockItem] list (the NFC tags/QR codes that can unblock it when
 * `physicalUnblockItems` gating is active on a strategy) - Android counterpart of iOS's physical
 * unblock item management (`PhysicalReader` + profile editor rows). Will be embedded directly into
 * the Phase 7 profile editor screen; exposed standalone here so it can be built/previewed/tested
 * independently.
 */
@Composable
fun PhysicalUnlockEditor(
    profileId: String,
    profileRepository: ProfileRepository,
    nfcDispatchManager: NfcForegroundDispatchManager,
    modifier: Modifier = Modifier,
    editingLocked: Boolean = false,
    onEditBlocked: () -> Unit = {},
) {
    val profile by profileRepository.observeById(profileId).collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    var activeScan by remember { mutableStateOf<ScanMode?>(null) }
    var renamingItem by remember { mutableStateOf<PhysicalUnblockItem?>(null) }

    val items = profile?.physicalUnblockItems.orEmpty()

    PhysicalUnlockEditorContent(
        items = items,
        onAddNfc = { if (editingLocked) onEditBlocked() else activeScan = ScanMode.NFC },
        onAddQr = { if (editingLocked) onEditBlocked() else activeScan = ScanMode.QR },
        onDelete = { item ->
            if (editingLocked) { onEditBlocked() } else scope.launch { updateItems(profileRepository, profileId) { current -> current.filterNot { it.id == item.id } } }
        },
        onRenameRequested = { item -> if (editingLocked) onEditBlocked() else renamingItem = item },
        modifier = modifier,
    )

    when (activeScan) {
        ScanMode.NFC -> NfcAddSheet(
            nfcDispatchManager = nfcDispatchManager,
            onScanned = { value ->
                scope.launch {
                    updateItems(profileRepository, profileId) { current ->
                        current + newItem(current, PhysicalUnblockType.NFC, value)
                    }
                }
                activeScan = null
            },
            onCancel = { activeScan = null },
        )
        ScanMode.QR -> QrAddSheet(
            onScanned = { value ->
                scope.launch {
                    updateItems(profileRepository, profileId) { current ->
                        current + newItem(current, PhysicalUnblockType.QR_CODE, value)
                    }
                }
                activeScan = null
            },
            onCancel = { activeScan = null },
        )
        null -> Unit
    }

    renamingItem?.let { item ->
        RenameDialog(
            item = item,
            onConfirm = { newName ->
                scope.launch {
                    updateItems(profileRepository, profileId) { current ->
                        current.map { if (it.id == item.id) it.copy(name = newName) else it }
                    }
                }
                renamingItem = null
            },
            onDismiss = { renamingItem = null },
        )
    }
}

private fun newItem(existing: List<PhysicalUnblockItem>, type: PhysicalUnblockType, codeValue: String): PhysicalUnblockItem {
    val label = if (type == PhysicalUnblockType.NFC) "NFC tag" else "QR code"
    val count = existing.count { it.type == type } + 1
    return PhysicalUnblockItem(name = "$label $count", type = type, codeValue = codeValue)
}

private suspend fun updateItems(
    profileRepository: ProfileRepository,
    profileId: String,
    transform: (List<PhysicalUnblockItem>) -> List<PhysicalUnblockItem>,
) {
    val profile = profileRepository.getById(profileId) ?: return
    profileRepository.upsert(profile.copy(physicalUnblockItems = transform(profile.physicalUnblockItems), updatedAt = Instant.now()))
}

/** Pure, previewable rendering of the item list + add buttons - no repository/NFC dependency. */
@Composable
fun PhysicalUnlockEditorContent(
    items: List<PhysicalUnblockItem>,
    onAddNfc: () -> Unit,
    onAddQr: () -> Unit,
    onDelete: (PhysicalUnblockItem) -> Unit,
    onRenameRequested: (PhysicalUnblockItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Physical unblock items", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Only these tags/codes will unblock this profile once at least one is added.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (items.isEmpty()) {
            Text(
                text = "No physical unblock items yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Plain Column + forEach (not LazyColumn): this composable is embedded inside
            // ProfileEditScreen's verticalScroll Column, and a LazyColumn nested in a
            // vertically-scrollable parent throws
            // "Vertically scrollable component was measured with an infinity maximum height
            // constraints" (IllegalStateException) as soon as it has content - this was the
            // crash-loop root cause when opening an existing profile. The item list here is
            // always short (a handful of NFC tags/QR codes), so a lazy list brings no benefit.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { item ->
                    key(item.id) {
                        PhysicalUnblockItemRow(item = item, onRename = { onRenameRequested(item) }, onDelete = { onDelete(item) })
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onAddNfc) { Text("🏷️ Add NFC tag") }
            OutlinedButton(onClick = onAddQr) { Text("🔳 Add QR code") }
        }
    }
}

@Composable
private fun PhysicalUnblockItemRow(item: PhysicalUnblockItem, onRename: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = if (item.type == PhysicalUnblockType.NFC) "🏷️" else "🔳")
                Column {
                    Text(text = item.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (item.type == PhysicalUnblockType.NFC) "NFC tag" else "QR code",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row {
                TextButton(onClick = onRename) { Text("Rename") }
                IconButton(onClick = onDelete) { Text("✕") }
            }
        }
    }
}

@Composable
private fun RenameDialog(item: PhysicalUnblockItem, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(item) { mutableStateOf(item.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { item.name }) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun NfcAddSheet(nfcDispatchManager: NfcForegroundDispatchManager, onScanned: (String) -> Unit, onCancel: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        nfcDispatchManager.setWaitingForScan(true)
        try {
            val content = nfcDispatchManager.tagScans.first()
            onScanned(content.value)
        } finally {
            nfcDispatchManager.setWaitingForScan(false)
        }
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text = "📡", style = MaterialTheme.typography.displayMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "Hold your phone near the NFC tag", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun QrAddSheet(onScanned: (String) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                QrScannerScreen(
                    onCode = onScanned,
                    modifier = Modifier.weight(1f),
                    title = "Scan to add",
                    subtitle = "Point your camera at a QR code or barcode to add it as a physical unblock item.",
                )
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) { Text("Cancel") }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PhysicalUnlockEditorContentPreview() {
    FoqosTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            PhysicalUnlockEditorContent(
                items = listOf(
                    PhysicalUnblockItem(name = "Desk tag", type = PhysicalUnblockType.NFC, codeValue = "foqos://profile/abc"),
                    PhysicalUnblockItem(name = "Fridge code", type = PhysicalUnblockType.QR_CODE, codeValue = "foqos://profile/xyz"),
                ),
                onAddNfc = {},
                onAddQr = {},
                onDelete = {},
                onRenameRequested = {},
            )
        }
    }
}
