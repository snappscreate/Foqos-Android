package com.foqos.android.service.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.foqos.android.domain.session.SessionManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Receives geofence transitions and changes only sessions owned by location automation. */
@AndroidEntryPoint
class GeofenceTransitionReceiver : BroadcastReceiver() {
    @Inject lateinit var sessionManager: SessionManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "Geofence error code=${event.errorCode}")
            return
        }
        val inside = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> true
            Geofence.GEOFENCE_TRANSITION_EXIT -> false
            else -> return
        }
        val store = LocationAutomationStore(context)
        val profileIds = event.triggeringGeofences.orEmpty().map { it.requestId }
            .filter { store.read(it) != null }.distinct()
        if (profileIds.isEmpty()) return
        val pending = goAsync()
        scope.launch {
            try {
                profileIds.forEach { profileId ->
                    // Each geofence transition changes only its owning profile.
                    store.clearManualInside(profileId)
                    store.setLastInside(profileId, inside)
                    sessionManager.onLocationTransition(profileId, inside)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Location transition failed", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "FoqosGeofence"
    }
}
