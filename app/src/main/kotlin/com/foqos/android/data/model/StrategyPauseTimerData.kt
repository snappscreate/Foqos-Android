package com.foqos.android.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Configuration payload for pause-timer strategies (e.g. NfcPauseTimerBlockingStrategy). */
@Serializable
data class StrategyPauseTimerData(
    val pauseDurationInMinutes: Int = DEFAULT_PAUSE_DURATION_MINUTES,
) {
    fun toJson(): String = modelJson.encodeToString(this)

    companion object {
        const val DEFAULT_PAUSE_DURATION_MINUTES = 15

        /**
         * Decodes [json] into [StrategyPauseTimerData]. Falls back to sensible defaults when
         * [json] is null/blank or fails to decode - this never returns null.
         */
        fun fromJsonOrNull(json: String?): StrategyPauseTimerData {
            if (json.isNullOrBlank()) return StrategyPauseTimerData()
            return try {
                modelJson.decodeFromString(json)
            } catch (e: Exception) {
                StrategyPauseTimerData()
            }
        }
    }
}
