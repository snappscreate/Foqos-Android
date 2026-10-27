package com.foqos.android.ui.settings

import android.Manifest
import android.location.Location
import android.content.Intent
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.service.location.GeofenceManager
import com.foqos.android.service.location.LocationAutomationStore
import com.foqos.android.service.location.LocationRule
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LocationAutomationViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val sessions: SessionManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val profiles = repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val store = LocationAutomationStore(context)
    val initialRule: LocationRule? = store.read()
    fun savedRule(): LocationRule? = store.read()
    var status by mutableStateOf("")
        private set

    fun hasPermission() = GeofenceManager(context).hasRequiredPermissions()

    fun save(profileId: String, latitude: String, longitude: String, radius: String) {
        if (sessions.activeSession.value != null) {
            status = "End the active blocking session before changing location automation."
            return
        }
        if (!hasPermission()) {
            status = "Grant precise location and Allow all the time in Android app permissions first."
            return
        }
        val rule = runCatching {
            LocationRule(profileId, latitude.toDouble(), longitude.toDouble(), radius.toFloat())
        }.getOrElse {
            status = "Enter valid coordinates and a radius of at least 100 meters."
            return
        }
        val manager = GeofenceManager(context)
        manager.register(rule)
            .addOnSuccessListener {
                val old = store.read()
                store.save(rule)
                if (old != null && old.profileId != rule.profileId) manager.unregister(old.profileId)
                status = "Location rule enabled. Android will monitor entry and exit."
            }
            .addOnFailureListener { status = "Could not register location rule: ${it.message}" }
    }

    fun disable() {
        if (sessions.activeSession.value != null) {
            status = "End the active blocking session before changing location automation."
            return
        }
        val rule = store.read() ?: return
        GeofenceManager(context).unregister(rule.profileId)
            .addOnSuccessListener {
                store.clear()
                status = "Location automation disabled."
            }
            .addOnFailureListener { status = "Could not disable location rule: ${it.message}" }
    }
}

@Composable
fun LocationAutomationSection(viewModel: LocationAutomationViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val profiles by viewModel.profiles.collectAsState()
    val original = viewModel.initialRule
    var selected by remember { mutableStateOf(original?.profileId.orEmpty()) }
    var lat by remember { mutableStateOf(original?.latitude?.toString().orEmpty()) }
    var lon by remember { mutableStateOf(original?.longitude?.toString().orEmpty()) }
    var radius by remember { mutableStateOf(original?.radiusMeters?.toInt()?.toString() ?: "200") }
    var expanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var distanceText by remember { mutableStateOf<String?>(null) }
    val foregroundRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        message = if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true)
            "Now grant 'Allow all the time' from Android app settings."
        else "Precise location permission is needed."
    }
    val locationClient = remember(context) { LocationServices.getFusedLocationProviderClient(context) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
        Text("Block automatically when entering the selected area. Leaving ends only a location-started session.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { expanded = true }) {
            Text(profiles.firstOrNull { it.id == selected }?.name ?: "Select profile")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(text = { Text(profile.name) }, onClick = {
                    selected = profile.id
                    expanded = false
                })
            }
        }
        OutlinedTextField(value = lat, onValueChange = { lat = it },
            label = { Text("Latitude") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = lon, onValueChange = { lon = it },
            label = { Text("Longitude") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = radius, onValueChange = { radius = it },
            label = { Text("Radius (meters, minimum 100)") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            foregroundRequest.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }) { Text("1. Allow precise location") }
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")))
        }) { Text("2. Allow location all the time in app settings") }
        OutlinedButton(onClick = {
            if (!viewModel.hasPermission()) {
                message = "First grant precise and background location permissions."
            } else {
                @Suppress("MissingPermission")
                locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { location ->
                        if (location != null) {
                            lat = location.latitude.toString()
                            lon = location.longitude.toString()
                            message = "Current position loaded. Check coordinates before saving."
                        } else {
                            message = "Could not determine location. Enter coordinates manually."
                        }
                    }.addOnFailureListener { message = "Location lookup failed: ${it.message}" }
            }
        }) { Text("Use current location") }
        OutlinedButton(onClick = {
            val rule = viewModel.savedRule()
            if (rule == null) {
                distanceText = "No saved blocking area."
            } else if (!viewModel.hasPermission()) {
                distanceText = "Location permission required."
            } else {
                @Suppress("MissingPermission")
                locationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { current ->
                        if (current == null) {
                            distanceText = "Current location unavailable."
                        } else {
                            val result = FloatArray(1)
                            Location.distanceBetween(
                                current.latitude, current.longitude,
                                rule.latitude, rule.longitude, result,
                            )
                            val remaining = (result[0] - rule.radiusMeters).coerceAtLeast(0f)
                            distanceText = if (remaining == 0f) "Inside saved blocking area" else
                                "${remaining.toInt()} m to saved blocking area"
                        }
                    }.addOnFailureListener {
                        distanceText = "Could not determine distance: ${it.message}"
                    }
            }
        }) { Text("Check distance to saved blocking area") }
        if (distanceText != null) Text(distanceText!!, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.save(selected, lat, lon, radius) }) {
                Text("Enable location rule")
            }
            OutlinedButton(onClick = viewModel::disable) { Text("Disable") }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (viewModel.status.isNotBlank()) Text(viewModel.status, style = MaterialTheme.typography.bodySmall)
    }
}
