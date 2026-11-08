package com.foqos.android.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.service.location.GeofenceManager
import com.foqos.android.service.location.LocationAutomationStore
import com.foqos.android.service.location.LocationRule
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class LocationAutomationViewModel @Inject constructor(
    private val sessions: SessionManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val store = LocationAutomationStore(context)
    var status by mutableStateOf("")
        private set

    fun savedRule(profileId: String): LocationRule? = store.read(profileId)
    fun hasPermission(): Boolean = GeofenceManager(context).hasRequiredPermissions()
    fun isLocked(profileId: String): Boolean =
        sessions.activeSessions.value.any { it.profileId == profileId }

    fun save(profileId: String, latitude: String, longitude: String, radius: String) {
        if (isLocked(profileId)) {
            status = "稼働中のプロファイルの位置範囲は変更できません"
            return
        }
        if (!hasPermission()) {
            status = "位置情報を「常に許可」にし、正確な位置情報を有効にしてください"
            return
        }
        val rule = runCatching {
            LocationRule(profileId, latitude.toDouble(), longitude.toDouble(), radius.toFloat())
        }.getOrElse {
            status = "緯度・経度と、100m以上の半径を入力してください"
            return
        }
        // Save before registration: INITIAL_TRIGGER_ENTER/EXIT can arrive immediately.
        // The receiver must be able to resolve this profile at that point.
        val previous = store.read(profileId)
        val wasEnabled = store.enabled(profileId)
        store.save(rule)
        store.setEnabled(profileId, true)
        GeofenceManager(context).register(rule)
            .addOnSuccessListener {
                status = "このプロファイルの位置範囲を保存しました"
                viewModelScope.launch { sessions.refreshActiveProfileConfig(profileId) }
            }
            .addOnFailureListener {
                if (previous == null) store.clear(profileId) else {
                    store.save(previous)
                    store.setEnabled(profileId, wasEnabled)
                }
                status = "位置範囲を登録できません：${it.message}"
            }
    }

    fun setAutomationEnabled(profileId: String, enabled: Boolean) {
        if (isLocked(profileId)) {
            status = "稼働中はブロック範囲を変更できません"
            return
        }
        val rule = store.read(profileId) ?: run {
            status = "先にブロック範囲を設定してね"
            return
        }
        if (enabled && !hasPermission()) {
            status = "正確な位置情報と「常に許可」が必要です"
            return
        }
        val old = store.enabled(profileId)
        store.setEnabled(profileId, enabled)
        val task = if (enabled) GeofenceManager(context).register(rule)
            else GeofenceManager(context).unregister(profileId)
        task.addOnFailureListener { error ->
            store.setEnabled(profileId, old)
            status = "位置連動を切り替えられません：${error.message}"
        }.addOnSuccessListener {
            status = if (enabled) "ブロック範囲を有効にしたよ" else "位置連動を無効にしたよ"
            viewModelScope.launch { sessions.refreshActiveProfileConfig(profileId) }
        }
    }

    fun disable(profileId: String) {
        if (isLocked(profileId)) {
            status = "稼働中のプロファイルの位置範囲は変更できません"
            return
        }
        if (store.read(profileId) == null) return
        GeofenceManager(context).unregister(profileId)
            .addOnSuccessListener {
                store.clear(profileId)
                status = "このプロファイルの位置範囲を削除しました"
            }
            .addOnFailureListener { status = "位置範囲を削除できません：${it.message}" }
    }
}

/** Embedded in the individual profile editor, not the global settings page. */
@Composable
fun LocationAutomationSection(
    profileId: String,
    editingLocked: Boolean,
    isPersisted: Boolean,
    viewModel: LocationAutomationViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val original = remember(profileId) { viewModel.savedRule(profileId) }
    var lat by remember(profileId) { mutableStateOf(original?.latitude?.toString().orEmpty()) }
    var lon by remember(profileId) { mutableStateOf(original?.longitude?.toString().orEmpty()) }
    var radius by remember(profileId) {
        mutableStateOf(original?.radiusMeters?.toInt()?.toString() ?: "200")
    }
    var message by remember { mutableStateOf("") }
    var distanceText by remember { mutableStateOf<String?>(null) }
    val canEdit = !editingLocked && isPersisted
    val foregroundRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        message = if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true)
            "次にAndroidのアプリ設定で位置情報を「常に許可」にしてください"
        else "正確な位置情報へのアクセスが必要です"
    }
    val locationClient = remember(context) { LocationServices.getFusedLocationProviderClient(context) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
        Text("このプロファイルにだけ適用する位置範囲を設定。範囲外ではこのプロファイルのブロックを停止し、範囲内では再開する。",
            style = MaterialTheme.typography.bodySmall)
        if (!isPersisted) {
            Text("位置範囲を設定するには、まずプロファイルを保存してください")
        }
        OutlinedTextField(value = lat, onValueChange = { lat = it },
            label = { Text("緯度") }, modifier = Modifier.fillMaxWidth(), enabled = canEdit)
        OutlinedTextField(value = lon, onValueChange = { lon = it },
            label = { Text("経度") }, modifier = Modifier.fillMaxWidth(), enabled = canEdit)
        OutlinedTextField(value = radius, onValueChange = { radius = it },
            label = { Text("半径（メートル、100以上）") }, modifier = Modifier.fillMaxWidth(), enabled = canEdit)
        OutlinedButton(onClick = {
            foregroundRequest.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }) { Text("位置情報へのアクセスを許可") }
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
        }) { Text("アプリ設定で「常に許可」を設定") }
        OutlinedButton(enabled = canEdit, onClick = {
            if (!viewModel.hasPermission()) {
                message = "正確な位置情報とバックグラウンド位置情報の権限が必要です"
            } else {
                @Suppress("MissingPermission")
                locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            lat = location.latitude.toString()
                            lon = location.longitude.toString()
                            message = "現在位置を取得しました。保存前に座標を確認してください"
                        } else message = "位置情報を取得できませんでした"
                    }.addOnFailureListener { message = "位置情報の取得に失敗：${it.message}" }
            }
        }) { Text("現在位置を入力") }
        OutlinedButton(onClick = {
            val rule = viewModel.savedRule(profileId)
            if (rule == null) {
                distanceText = "このプロファイルに位置範囲は登録されていません"
            } else if (!viewModel.hasPermission()) {
                distanceText = "位置情報の権限が必要です"
            } else {
                @Suppress("MissingPermission")
                locationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { current ->
                        if (current == null) distanceText = "現在位置を取得できませんでした"
                        else {
                            val results = FloatArray(1)
                            Location.distanceBetween(current.latitude, current.longitude,
                                rule.latitude, rule.longitude, results)
                            val remaining = (results[0] - rule.radiusMeters).coerceAtLeast(0f)
                            distanceText = if (remaining == 0f) "ブロック範囲内です" else
                                "ブロック範囲まで約${remaining.toInt()}m"
                        }
                    }.addOnFailureListener { distanceText = "距離を取得できません：${it.message}" }
            }
        }) { Text("登録済みの範囲までの距離") }
        if (distanceText != null) Text(distanceText!!, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = canEdit, onClick = { viewModel.save(profileId, lat, lon, radius) }) {
                Text("位置範囲を保存")
            }
            OutlinedButton(enabled = canEdit, onClick = { viewModel.disable(profileId) }) {
                Text("位置範囲を削除")
            }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (viewModel.status.isNotBlank()) Text(viewModel.status, style = MaterialTheme.typography.bodySmall)
    }
}
