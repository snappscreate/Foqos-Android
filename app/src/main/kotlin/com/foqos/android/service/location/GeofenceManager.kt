package com.foqos.android.service.location

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task

/**
 * Registration only: no location samples are stored or uploaded.
 * Callers must first request both foreground precise location and background location.
 * Registration is NOT invoked until profile UI and lifecycle recovery are implemented.
 */
class GeofenceManager(private val context: Context) {
    private val client = LocationServices.getGeofencingClient(context)

    fun hasRequiredPermissions(): Boolean {
        val foreground = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        return foreground && background
    }

    @Suppress("MissingPermission")
    fun register(rule: LocationRule): Task<Void> {
        check(hasRequiredPermissions()) { "Precise and background location permissions are required" }
        val fence = Geofence.Builder()
            .setRequestId(rule.profileId)
            .setCircularRegion(rule.latitude, rule.longitude, rule.radiusMeters)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(if (LocationAutomationStore(context).manualInside() != null) 0 else GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(fence)
            .build()
        return client.addGeofences(request, pendingIntent())
    }

    fun unregister(profileId: String): Task<Void> = client.removeGeofences(listOf(profileId))

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceTransitionReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            7001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
