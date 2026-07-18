package com.foqos.android.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Configuration payload for soft-unblock strategies (e.g. NfcSoftUnblockBlockingStrategy). Values
 * are clamped to their valid ranges via [normalized], mirroring iOS behavior.
 */
@Serializable
data class SoftUnblockStrategyData(
    val accessDurationInMinutes: Int = DEFAULT_DURATION_MINUTES,
    val maximumUnblockCount: Int = DEFAULT_MAXIMUM_UNBLOCK_COUNT,
    val allowanceResetIntervalInHours: Int? = DEFAULT_ALLOWANCE_RESET_INTERVAL_HOURS,
) {

    /** This configuration with all fields clamped to their valid ranges. */
    val normalized: SoftUnblockStrategyData
        get() = SoftUnblockStrategyData(
            accessDurationInMinutes = accessDurationInMinutes.coerceIn(DURATION_RANGE),
            maximumUnblockCount = maximumUnblockCount.coerceIn(UNBLOCK_COUNT_RANGE),
            allowanceResetIntervalInHours = allowanceResetIntervalInHours?.takeIf {
                it in ALLOWANCE_RESET_INTERVALS_HOURS
            },
        )

    fun toJson(): String = modelJson.encodeToString(normalized)

    companion object {
        const val DEFAULT_DURATION_MINUTES = 15
        const val DEFAULT_MAXIMUM_UNBLOCK_COUNT = 3
        val DEFAULT_ALLOWANCE_RESET_INTERVAL_HOURS: Int? = null

        val DURATION_RANGE = 5..60
        val UNBLOCK_COUNT_RANGE = 1..10
        val ALLOWANCE_RESET_INTERVALS_HOURS = listOf(6, 12, 24)

        /**
         * Decodes [json] into a normalized [SoftUnblockStrategyData]. Falls back to normalized
         * defaults when [json] is null/blank or fails to decode - this never returns null.
         */
        fun fromJsonOrNull(json: String?): SoftUnblockStrategyData {
            if (json.isNullOrBlank()) return SoftUnblockStrategyData().normalized
            return try {
                modelJson.decodeFromString<SoftUnblockStrategyData>(json).normalized
            } catch (e: Exception) {
                SoftUnblockStrategyData().normalized
            }
        }
    }
}
