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
        val rule = store.read() ?: return
        if (event.triggeringGeofences.orEmpty().none { it.requestId == rule.profileId }) return
        val pending = goAsync()
        scope.launch {
            try {
                // A real transition supersedes the developer override, not a GPS poll.
                store.clearManualInside()
                store.setLastInside(inside)
                sessionManager.onLocationTransition(rule.profileId, inside)
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
