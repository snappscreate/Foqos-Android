package com.foqos.android.service.location

/**
 * Persistable definition for a geographic blocking rule.
 *
 * A later UI/SessionManager integration will associate [profileId] with the monitored area.
 * Defining the rule does not begin monitoring or alter an active session.
 */
data class LocationRule(
    val profileId: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 200f,
) {
    init {
        require(profileId.isNotBlank()) { "profileId must not be empty" }
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude" }
        require(radiusMeters.isFinite() && radiusMeters >= 100f) { "Geofence radius must be at least 100m" }
    }
}
