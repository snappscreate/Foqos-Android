package com.foqos.android.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Configuration payload for timer-based strategies (e.g. NfcTimerBlockingStrategy,
 * TimerManualBlockingStrategy). [hideStopButton] hides the in-app Stop button until the timer
 * has elapsed.
 */
@Serializable
data class StrategyTimerData(
    val durationInMinutes: Int = DEFAULT_DURATION_MINUTES,
    val hideStopButton: Boolean = false,
) {
    fun toJson(): String = modelJson.encodeToString(this)

    companion object {
        const val DEFAULT_DURATION_MINUTES = 15

        /**
         * Decodes [json] into [StrategyTimerData]. Falls back to sensible defaults (matching iOS
         * behavior) when [json] is null/blank or fails to decode - this never returns null.
         */
        fun fromJsonOrNull(json: String?): StrategyTimerData {
            if (json.isNullOrBlank()) return StrategyTimerData()
            return try {
                modelJson.decodeFromString(json)
            } catch (e: Exception) {
                StrategyTimerData()
            }
        }
    }
}
