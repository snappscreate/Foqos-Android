package com.foqos.android.data.model

import com.foqos.android.data.db.entity.SoftUnblockGrantEntity
import com.foqos.android.data.db.entity.SoftUnblockSessionStateEntity
import java.time.Instant

/**
 * Domain-facing representation of a single soft-unblock grant (parsed form of
 * [SoftUnblockGrantEntity]), mirroring iOS `SoftUnblockGrant`.
 */
data class SoftUnblockGrant(
    val id: String,
    val sessionId: String,
    val profileId: String,
    val packageName: String,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    fun isExpired(at: Instant = Instant.now()): Boolean = !expiresAt.isAfter(at)
}

/**
 * Domain-facing representation of the soft-unblock allowance state for a session (parsed form of
 * [SoftUnblockSessionStateEntity]), mirroring iOS `SoftUnblockSessionState`.
 */
data class SoftUnblockSessionState(
    val sessionId: String,
    val profileId: String,
    val maximumUnblockCount: Int,
    val allowanceResetIntervalInHours: Int? = null,
    val allowanceWindowStartedAt: Instant,
    val nextAllowanceResetAt: Instant? = null,
    val usedUnblockCount: Int = 0,
) {

    val remainingUnblockCount: Int
        get() = maxOf(maximumUnblockCount - usedUnblockCount, 0)

    /**
     * Returns this state with its allowance window/count reset if [at] is at or past
     * [nextAllowanceResetAt], mirroring iOS `resetAllowanceIfNeeded`. Returns `this` unchanged if
     * no reset is configured or none is due yet.
     */
    fun resetAllowanceIfNeeded(at: Instant): SoftUnblockSessionState {
        val intervalHours = allowanceResetIntervalInHours ?: return this
        val nextReset = nextAllowanceResetAt ?: return this
        if (at.isBefore(nextReset)) return this

        val intervalMillis = intervalHours * 60L * 60L * 1000L
        val elapsedIntervals = ((at.toEpochMilli() - nextReset.toEpochMilli()) / intervalMillis) + 1
        val newWindowStart = nextReset.plusMillis((elapsedIntervals - 1) * intervalMillis)
        val newNextReset = nextReset.plusMillis(elapsedIntervals * intervalMillis)

        return copy(
            allowanceWindowStartedAt = newWindowStart,
            nextAllowanceResetAt = newNextReset,
            usedUnblockCount = 0,
        )
    }

    /** Whether a grant created at [date] falls within the currently active allowance window. */
    fun containsAllowanceUse(date: Instant): Boolean {
        if (date.isBefore(allowanceWindowStartedAt)) return false
        val nextReset = nextAllowanceResetAt ?: return true
        return date.isBefore(nextReset)
    }
}

fun SoftUnblockGrantEntity.toDomain(): SoftUnblockGrant = SoftUnblockGrant(
    id = id,
    sessionId = sessionId,
    profileId = profileId,
    packageName = packageName,
    createdAt = Instant.ofEpochMilli(createdAt),
    expiresAt = Instant.ofEpochMilli(expiresAt),
)

fun SoftUnblockGrant.toEntity(): SoftUnblockGrantEntity = SoftUnblockGrantEntity(
    id = id,
    sessionId = sessionId,
    profileId = profileId,
    packageName = packageName,
    createdAt = createdAt.toEpochMilli(),
    expiresAt = expiresAt.toEpochMilli(),
)

fun SoftUnblockSessionStateEntity.toDomain(): SoftUnblockSessionState = SoftUnblockSessionState(
    sessionId = sessionId,
    profileId = profileId,
    maximumUnblockCount = maximumUnblockCount,
    allowanceResetIntervalInHours = allowanceResetIntervalInHours,
    allowanceWindowStartedAt = Instant.ofEpochMilli(allowanceWindowStartedAt),
    nextAllowanceResetAt = nextAllowanceResetAt?.let { Instant.ofEpochMilli(it) },
    usedUnblockCount = usedUnblockCount,
)

fun SoftUnblockSessionState.toEntity(): SoftUnblockSessionStateEntity = SoftUnblockSessionStateEntity(
    sessionId = sessionId,
    profileId = profileId,
    maximumUnblockCount = maximumUnblockCount,
    allowanceResetIntervalInHours = allowanceResetIntervalInHours,
    allowanceWindowStartedAt = allowanceWindowStartedAt.toEpochMilli(),
    nextAllowanceResetAt = nextAllowanceResetAt?.toEpochMilli(),
    usedUnblockCount = usedUnblockCount,
)
