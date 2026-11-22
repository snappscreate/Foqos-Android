package com.foqos.android.ui.activitylab

import android.content.Context

/** One pending reservation per profile, bound to a particular active session. */
data class PauseReservation(
    val sessionId: String,
    val startAtMillis: Long,
    val pauseMinutes: Int,
)

class PauseReservationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "pause_reservations_v1", Context.MODE_PRIVATE
    )

    fun read(profileId: String): PauseReservation? {
        val sessionId = prefs.getString("session_$profileId", null) ?: return null
        val at = prefs.getLong("at_$profileId", 0L)
        val minutes = prefs.getInt("duration_$profileId", 0)
        return if (at > 0L && minutes in 1..1440)
            PauseReservation(sessionId, at, minutes) else null
    }

    fun save(profileId: String, reservation: PauseReservation) {
        require(reservation.startAtMillis > 0L && reservation.pauseMinutes in 1..1440)
        check(prefs.edit()
            .putString("session_$profileId", reservation.sessionId)
            .putLong("at_$profileId", reservation.startAtMillis)
            .putInt("duration_$profileId", reservation.pauseMinutes)
            .commit()) { "一時休止予約を保存できなかった" }
    }

    fun clear(profileId: String) {
        check(prefs.edit().remove("session_$profileId")
            .remove("at_$profileId").remove("duration_$profileId").commit()) {
            "一時休止予約を解除できなかった"
        }
    }
}
