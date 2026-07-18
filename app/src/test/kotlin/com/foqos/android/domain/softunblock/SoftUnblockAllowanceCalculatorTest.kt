package com.foqos.android.domain.softunblock

import com.foqos.android.data.model.SoftUnblockSessionState
import com.foqos.android.data.model.SoftUnblockStrategyData
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.UUID
import org.junit.Test

/**
 * Port of iOS `foqosTests/SoftUnblockTests.swift` (minus the DeviceActivity activity-identifier
 * parsing tests, which cover Phase 4's alarm-scheduling layer, not domain logic), plus an explicit
 * allowance-exhaustion test for [SoftUnblockAllowanceCalculator.canIssueGrant].
 */
class SoftUnblockAllowanceCalculatorTest {

    @Test
    fun `strategy configuration round trips`() {
        val configuration = SoftUnblockStrategyData(
            accessDurationInMinutes = 30,
            maximumUnblockCount = 6,
            allowanceResetIntervalInHours = 12,
        )

        val decoded = SoftUnblockStrategyData.fromJsonOrNull(configuration.toJson())

        assertThat(decoded).isEqualTo(configuration)
    }

    @Test
    fun `strategy configuration falls back for missing data`() {
        val configuration = SoftUnblockStrategyData.fromJsonOrNull(null)

        assertThat(configuration.accessDurationInMinutes).isEqualTo(SoftUnblockStrategyData.DEFAULT_DURATION_MINUTES)
        assertThat(configuration.maximumUnblockCount).isEqualTo(SoftUnblockStrategyData.DEFAULT_MAXIMUM_UNBLOCK_COUNT)
        assertThat(configuration.allowanceResetIntervalInHours).isNull()
    }

    @Test
    fun `allowance does not reset before boundary`() {
        val start = Instant.ofEpochSecond(1_000_000)
        val state = makeState(startedAt = start, resetHours = 6, usedUnblocks = 2)
        val justBeforeBoundary = start.plusSeconds(6 * 60 * 60 - 1)

        assertThat(SoftUnblockAllowanceCalculator.shouldReset(state, justBeforeBoundary)).isFalse()
        assertThat(SoftUnblockAllowanceCalculator.nextStateAfterReset(state, justBeforeBoundary).usedUnblockCount)
            .isEqualTo(2)
    }

    @Test
    fun `allowance resets at boundary`() {
        val start = Instant.ofEpochSecond(1_000_000)
        val state = makeState(startedAt = start, resetHours = 6, usedUnblocks = 2)
        val boundary = start.plusSeconds(6 * 60 * 60)

        assertThat(SoftUnblockAllowanceCalculator.shouldReset(state, boundary)).isTrue()

        val reset = SoftUnblockAllowanceCalculator.nextStateAfterReset(state, boundary)
        assertThat(reset.usedUnblockCount).isEqualTo(0)
        assertThat(reset.allowanceWindowStartedAt).isEqualTo(boundary)
        assertThat(reset.nextAllowanceResetAt).isEqualTo(boundary.plusSeconds(6 * 60 * 60))
    }

    @Test
    fun `allowance advances across missed windows`() {
        val start = Instant.ofEpochSecond(1_000_000)
        val state = makeState(startedAt = start, resetHours = 6, usedUnblocks = 2)
        val currentDate = start.plusSeconds(19 * 60 * 60 + 10)

        val reset = SoftUnblockAllowanceCalculator.nextStateAfterReset(state, currentDate)

        assertThat(reset.usedUnblockCount).isEqualTo(0)
        assertThat(reset.allowanceWindowStartedAt).isEqualTo(start.plusSeconds(18 * 60 * 60))
        assertThat(reset.nextAllowanceResetAt).isEqualTo(start.plusSeconds(24 * 60 * 60))
    }

    @Test
    fun `never-reset configuration preserves usage`() {
        val start = Instant.ofEpochSecond(1_000_000)
        val state = makeState(startedAt = start, resetHours = null, usedUnblocks = 2)

        assertThat(SoftUnblockAllowanceCalculator.shouldReset(state, start.plusSeconds(48 * 60 * 60))).isFalse()
        assertThat(
            SoftUnblockAllowanceCalculator.nextStateAfterReset(state, start.plusSeconds(48 * 60 * 60)).usedUnblockCount,
        ).isEqualTo(2)
    }

    @Test
    fun `rollback only matches the current allowance window`() {
        val start = Instant.ofEpochSecond(1_000_000)
        val state = makeState(startedAt = start, resetHours = 6, usedUnblocks = 2)
        val resetBoundary = start.plusSeconds(6 * 60 * 60)
        val reset = SoftUnblockAllowanceCalculator.nextStateAfterReset(state, resetBoundary)

        assertThat(reset.containsAllowanceUse(resetBoundary.minusSeconds(1))).isFalse()
        assertThat(reset.containsAllowanceUse(resetBoundary.plusSeconds(1))).isTrue()
    }

    @Test
    fun `cannot issue a grant once the allowance is exhausted`() {
        val start = Instant.ofEpochSecond(1_000_000)
        val exhausted = makeState(startedAt = start, resetHours = null, usedUnblocks = 3, maximumUnblockCount = 3)

        assertThat(SoftUnblockAllowanceCalculator.remainingCount(exhausted, start)).isEqualTo(0)
        assertThat(SoftUnblockAllowanceCalculator.canIssueGrant(exhausted, start)).isFalse()
        // Never resets, so it stays exhausted arbitrarily far into the future.
        assertThat(SoftUnblockAllowanceCalculator.canIssueGrant(exhausted, start.plusSeconds(365L * 24 * 60 * 60)))
            .isFalse()
    }

    @Test
    fun `exhausted allowance becomes issuable again once its window resets`() {
        val start = Instant.ofEpochSecond(1_000_000)
        val exhausted = makeState(startedAt = start, resetHours = 6, usedUnblocks = 3, maximumUnblockCount = 3)

        assertThat(SoftUnblockAllowanceCalculator.canIssueGrant(exhausted, start.plusSeconds(3 * 60 * 60))).isFalse()
        assertThat(SoftUnblockAllowanceCalculator.canIssueGrant(exhausted, start.plusSeconds(6 * 60 * 60))).isTrue()
        assertThat(SoftUnblockAllowanceCalculator.remainingCount(exhausted, start.plusSeconds(6 * 60 * 60)))
            .isEqualTo(3)
    }

    private fun makeState(
        startedAt: Instant,
        resetHours: Int?,
        usedUnblocks: Int,
        maximumUnblockCount: Int = 3,
    ): SoftUnblockSessionState = SoftUnblockSessionState(
        sessionId = UUID.randomUUID().toString(),
        profileId = UUID.randomUUID().toString(),
        maximumUnblockCount = maximumUnblockCount,
        allowanceResetIntervalInHours = resetHours,
        allowanceWindowStartedAt = startedAt,
        nextAllowanceResetAt = resetHours?.let { startedAt.plusSeconds(it * 60L * 60L) },
        usedUnblockCount = usedUnblocks,
    )
}
