package com.foqos.android.service.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * First-stage diagnostic receiver. It must not force-stop or start sessions until
 * location rule persistence, ownership precedence, and reboot restoration exist.
 */
class GeofenceTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "Geofence event error code=${event.errorCode}")
            return
        }
        val label = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            else -> return
        }
        event.triggeringGeofences.orEmpty().forEach { fence ->
            // Profile IDs and coordinates are not logged.
            Log.d(TAG, "Geofence transition=$label")
        }
    }

    companion object {
        private const val TAG = "FoqosGeofence"
    }
}
