package com.foqos.android.domain.session

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.StrategyTimerData
import com.foqos.android.domain.strategy.ManualBlockingStrategy
import com.foqos.android.domain.strategy.NfcTimerBlockingStrategy
import com.foqos.android.domain.strategy.QrTimerBlockingStrategy
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/** Port of iOS `foqosTests/SessionTimeCalculatorTests.swift` - same scenarios and expected values. */
class SessionTimeCalculatorTest {

    @Test
    fun `timer session displays remaining time`() {
        val startTime = Instant.ofEpochSecond(1_000)
        val profile = makeProfile(strategyId = NfcTimerBlockingStrategy.ID, durationInMinutes = 60)
        val session = session(profile, tag = NfcTimerBlockingStrategy.ID, startTime = startTime)

        val currentTime = startTime.plusSeconds(15 * 60)
        val elapsed = SessionTimeCalculator.elapsedFocusTime(session, profile, currentTime)
        val displayed = SessionTimeCalculator.displayedTime(session, profile, elapsed, currentTime)

        assertThat(elapsed.seconds).isEqualTo(15 * 60L)
        assertThat(displayed.seconds).isEqualTo(45 * 60L)
    }

    @Test
    fun `manual session displays elapsed time`() {
        val startTime = Instant.ofEpochSecond(1_000)
        val profile = makeProfile(strategyId = ManualBlockingStrategy.ID)
        val session = session(profile, tag = ManualBlockingStrategy.ID, startTime = startTime)

        val currentTime = startTime.plusSeconds(20 * 60)
        val displayed = SessionTimeCalculator.displayedTime(session, profile, at = currentTime)

        assertThat(displayed.seconds).isEqualTo(20 * 60L)
    }

    @Test
    fun `timer display matches expected end time after a completed break`() {
        val startTime = Instant.ofEpochSecond(1_000)
        val profile = makeProfile(strategyId = QrTimerBlockingStrategy.ID, durationInMinutes = 60, enableBreaks = true)
        val session = session(
            profile,
            tag = QrTimerBlockingStrategy.ID,
            startTime = startTime,
            breakStartTime = startTime.plusSeconds(10 * 60),
            breakEndTime = startTime.plusSeconds(20 * 60),
        )

        val currentTime = startTime.plusSeconds(30 * 60)
        val elapsed = SessionTimeCalculator.elapsedFocusTime(session, profile, currentTime)
        val displayed = SessionTimeCalculator.displayedTime(session, profile, elapsed, currentTime)

        assertThat(elapsed.seconds).isEqualTo(20 * 60L)
        assertThat(displayed.seconds).isEqualTo(30 * 60L)
        assertThat(SessionTimeCalculator.expectedEndTime(session, profile, currentTime))
            .isEqualTo(startTime.plusSeconds(60 * 60))
    }

    @Test
    fun `single break is unavailable after it ends`() {
        val startTime = Instant.ofEpochSecond(1_000)
        val profile = makeProfile(
            strategyId = ManualBlockingStrategy.ID,
            enableBreaks = true,
            breakTimeInMinutes = 15,
            allowMultipleBreaks = false,
        )
        val session = session(
            profile,
            tag = ManualBlockingStrategy.ID,
            startTime = startTime,
            breakStartTime = startTime.plusSeconds(5 * 60),
            breakEndTime = startTime.plusSeconds(8 * 60),
        )

        assertThat(SessionTimeCalculator.isBreakAvailable(session, profile)).isFalse()
        assertThat(SessionTimeCalculator.elapsedFocusTime(session, profile, startTime.plusSeconds(10 * 60)).seconds)
            .isEqualTo(7 * 60L)
    }

    @Test
    fun `reusable break stopped early leaves remaining allowance`() {
        val startTime = Instant.ofEpochSecond(1_000)
        val profile = makeProfile(
            strategyId = ManualBlockingStrategy.ID,
            enableBreaks = true,
            breakTimeInMinutes = 15,
            allowMultipleBreaks = true,
        )
        val session = session(
            profile,
            tag = ManualBlockingStrategy.ID,
            startTime = startTime,
            breakStartTime = startTime.plusSeconds(5 * 60),
            breakEndTime = startTime.plusSeconds(8 * 60),
            usedBreakDurationInSeconds = 3 * 60,
        )

        assertThat(SessionTimeCalculator.isBreakAvailable(session, profile)).isTrue()
        assertThat(SessionTimeCalculator.remainingBreakAllowance(session, profile).seconds).isEqualTo(12 * 60L)
    }

    @Test
    fun `reusable break displays remaining allowance during second break`() {
        val startTime = Instant.ofEpochSecond(1_000)
        val profile = makeProfile(
            strategyId = ManualBlockingStrategy.ID,
            enableBreaks = true,
            breakTimeInMinutes = 15,
            allowMultipleBreaks = true,
        )
        val session = session(
            profile,
            tag = ManualBlockingStrategy.ID,
            startTime = startTime,
            usedBreakDurationInSeconds = 3 * 60,
            breakStartTime = startTime.plusSeconds(10 * 60),
        )

        val currentTime = startTime.plusSeconds(12 * 60)
        val elapsed = SessionTimeCalculator.elapsedFocusTime(session, profile, currentTime)
        val displayed = SessionTimeCalculator.displayedTime(session, profile, elapsed, currentTime)

        assertThat(elapsed.seconds).isEqualTo(7 * 60L)
        assertThat(displayed.seconds).isEqualTo(10 * 60L)
    }

    @Test
    fun `reusable break unavailable when allowance is exhausted`() {
        val profile = makeProfile(
            strategyId = ManualBlockingStrategy.ID,
            enableBreaks = true,
            breakTimeInMinutes = 15,
            allowMultipleBreaks = true,
        )
        val session = session(profile, tag = ManualBlockingStrategy.ID, usedBreakDurationInSeconds = 15 * 60)

        assertThat(SessionTimeCalculator.isBreakAvailable(session, profile)).isFalse()
        assertThat(SessionTimeCalculator.remainingBreakAllowance(session, profile).seconds).isEqualTo(0L)
    }

    @Test
    fun `reusable break allowance resets for new session`() {
        val profile = makeProfile(
            strategyId = ManualBlockingStrategy.ID,
            enableBreaks = true,
            breakTimeInMinutes = 15,
            allowMultipleBreaks = true,
        )
        val session = session(profile, tag = ManualBlockingStrategy.ID)

        assertThat(SessionTimeCalculator.isBreakAvailable(session, profile)).isTrue()
        assertThat(SessionTimeCalculator.remainingBreakAllowance(session, profile).seconds).isEqualTo(15 * 60L)
    }

    private fun makeProfile(
        strategyId: String,
        durationInMinutes: Int? = null,
        enableBreaks: Boolean = false,
        breakTimeInMinutes: Int = 15,
        allowMultipleBreaks: Boolean = false,
    ): BlockedProfile {
        val strategyDataJson = durationInMinutes?.let {
            StrategyTimerData(durationInMinutes = it, hideStopButton = false).toJson()
        }

        return BlockedProfile(
            id = "profile-1",
            name = "Focus",
            strategyId = strategyId,
            strategyDataJson = strategyDataJson,
            enableBreaks = enableBreaks,
            breakTimeInMinutes = breakTimeInMinutes,
            allowMultipleBreaks = allowMultipleBreaks,
        )
    }

    private fun session(
        profile: BlockedProfile,
        tag: String,
        startTime: Instant = Instant.now(),
        breakStartTime: Instant? = null,
        breakEndTime: Instant? = null,
        usedBreakDurationInSeconds: Long = 0,
    ): BlockedProfileSession = BlockedProfileSession(
        id = "session-1",
        profileId = profile.id,
        tag = tag,
        startTime = startTime,
        breakStartTime = breakStartTime,
        breakEndTime = breakEndTime,
        usedBreakDurationInSeconds = usedBreakDurationInSeconds,
    )
}
