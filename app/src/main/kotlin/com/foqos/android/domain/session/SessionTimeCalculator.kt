package com.foqos.android.domain.session

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.foqos.android.data.model.StrategyPauseTimerData
import com.foqos.android.data.model.StrategyTimerData
import com.foqos.android.domain.strategy.NfcTimerBlockingStrategy
import com.foqos.android.domain.strategy.QrTimerBlockingStrategy
import com.foqos.android.domain.strategy.StrategyRegistry
import com.foqos.android.domain.strategy.TimerManualBlockingStrategy
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Computes how much of a session counts as focus time (elapsed break/pause time is excluded) and
 * how much time should be displayed to the user (remaining time for timer/scheduled/break/pause
 * sessions, elapsed time otherwise). Mirrors iOS `SessionTimeCalculator` exactly, including its
 * asymmetry: [elapsedFocusTime] only ever subtracts *break* time, never pause time - a paused
 * session keeps accumulating focus time in iOS, it only affects what's *displayed* via
 * [expectedEndTime].
 */
object SessionTimeCalculator {

    private val timerStrategyIds: Set<String> = setOf(
        NfcTimerBlockingStrategy.ID,
        QrTimerBlockingStrategy.ID,
        TimerManualBlockingStrategy.ID,
    )

    /** Focus time elapsed so far, with any break time (completed or in-progress) subtracted out. */
    fun elapsedFocusTime(
        session: BlockedProfileSession,
        profile: BlockedProfile,
        at: Instant = Instant.now(),
    ): Duration {
        val rawElapsed = Duration.between(session.startTime, at)
        val breakDuration = calculateBreakDuration(session, profile, at)
        val result = rawElapsed.minus(breakDuration)
        return if (result.isNegative) Duration.ZERO else result
    }

    /**
     * The time to display to the user: remaining time until [expectedEndTime] if one applies
     * (pause/break/schedule/timer sessions), otherwise the elapsed focus time.
     */
    fun displayedTime(
        session: BlockedProfileSession,
        profile: BlockedProfile,
        elapsedFocusTime: Duration? = null,
        at: Instant = Instant.now(),
    ): Duration {
        val expectedEnd = expectedEndTime(session, profile, at)
        if (expectedEnd != null) {
            val remaining = Duration.between(at, expectedEnd)
            return if (remaining.isNegative) Duration.ZERO else remaining
        }
        return elapsedFocusTime ?: elapsedFocusTime(session, profile, at)
    }

    /**
     * When this session is expected to end on its own (pause end, break end, schedule end, or
     * timer end), or `null` if the session only ends when explicitly stopped.
     */
    fun expectedEndTime(
        session: BlockedProfileSession,
        profile: BlockedProfile,
        at: Instant = Instant.now(),
    ): Instant? {
        val pauseStartTime = session.pauseStartTime
        if (pauseStartTime != null && session.isPauseActive(at)) {
            return pauseStartTime.plus(pauseDurationInSeconds(profile))
        }

        val breakStartTime = session.breakStartTime
        if (breakStartTime != null && session.isBreakActive(at)) {
            return if (profile.allowMultipleBreaks) {
                val remainingAllowanceAtBreakStart = remainingBreakAllowance(session, profile, breakStartTime)
                breakStartTime.plus(remainingAllowanceAtBreakStart)
            } else {
                breakStartTime.plus(totalBreakAllowanceInSeconds(profile))
            }
        }

        if (isScheduledSession(session, profile, at)) {
            val durationInSeconds = profile.schedule?.totalDurationInSeconds ?: return null
            if (durationInSeconds <= 0) return null
            return session.startTime.plusSeconds(durationInSeconds.toLong())
        }

        if (isTimerSession(session, profile)) {
            val timerDuration = timerDurationInSeconds(profile) ?: return null
            return session.startTime.plus(timerDuration)
        }

        return null
    }

    /** Whether [session] is running under one of the timer strategies (by tag or profile strategy). */
    fun isTimerSession(session: BlockedProfileSession, profile: BlockedProfile): Boolean =
        session.tag in timerStrategyIds || (profile.strategyId ?: "") in timerStrategyIds

    /** The configured timer duration for [profile], or `null` if no strategy data is stored. */
    fun timerDurationInSeconds(profile: BlockedProfile): Duration? {
        val json = profile.strategyDataJson ?: return null
        val timerData = StrategyTimerData.fromJsonOrNull(json)
        return Duration.ofMinutes(timerData.durationInMinutes.toLong())
    }

    /** Whether this session's break allowance can still be used (see iOS `isBreakAvailable`). */
    fun isBreakAvailable(session: BlockedProfileSession, profile: BlockedProfile, at: Instant = Instant.now()): Boolean {
        if (!profile.enableBreaks || !allowsTimedBreaks(profile)) return false

        return if (profile.allowMultipleBreaks) {
            remainingBreakAllowance(session, profile, at) > Duration.ZERO
        } else {
            session.breakEndTime == null
        }
    }

    /** Remaining break allowance at [at], accounting for any break currently in progress. */
    fun remainingBreakAllowance(
        session: BlockedProfileSession,
        profile: BlockedProfile,
        at: Instant = Instant.now(),
    ): Duration {
        val remaining = totalBreakAllowanceInSeconds(profile).minus(usedBreakDurationIncludingActiveBreak(session, profile, at))
        return if (remaining.isNegative) Duration.ZERO else remaining
    }

    /** Total configured break allowance for [profile]. */
    fun totalBreakAllowanceInSeconds(profile: BlockedProfile): Duration =
        Duration.ofMinutes(profile.breakTimeInMinutes.toLong())

    /** Break time used so far, including any break currently in progress. */
    fun usedBreakDurationIncludingActiveBreak(
        session: BlockedProfileSession,
        profile: BlockedProfile,
        at: Instant = Instant.now(),
    ): Duration {
        if (profile.allowMultipleBreaks) {
            val used = Duration.ofSeconds(session.usedBreakDurationInSeconds).plus(activeBreakElapsedTime(session, at))
            val total = totalBreakAllowanceInSeconds(profile)
            return if (used > total) total else used
        }
        return completedSingleBreakDuration(session, at)
    }

    /** Elapsed time of the currently in-progress break, or zero if no break is active. */
    fun activeBreakElapsedTime(session: BlockedProfileSession, at: Instant = Instant.now()): Duration {
        val breakStartTime = session.breakStartTime
        if (!session.isBreakActive(at) || breakStartTime == null) return Duration.ZERO
        val elapsed = Duration.between(breakStartTime, at)
        return if (elapsed.isNegative) Duration.ZERO else elapsed
    }

    /** Whether timed breaks are allowed at all for [profile]'s configured strategy. */
    fun allowsTimedBreaks(profile: BlockedProfile): Boolean =
        profile.allowsTimedBreaks { strategyId -> StrategyRegistry.byId(strategyId).allowsTimedBreaks }

    private fun calculateBreakDuration(session: BlockedProfileSession, profile: BlockedProfile, at: Instant): Duration {
        if (profile.allowMultipleBreaks) {
            return usedBreakDurationIncludingActiveBreak(session, profile, at)
        }
        return completedSingleBreakDuration(session, at)
    }

    private fun completedSingleBreakDuration(session: BlockedProfileSession, at: Instant): Duration {
        val breakStartTime = session.breakStartTime ?: return Duration.ZERO

        val breakEndTime = session.breakEndTime
        if (breakEndTime != null) {
            val duration = Duration.between(breakStartTime, breakEndTime)
            return if (duration.isNegative) Duration.ZERO else duration
        }

        if (session.isBreakActive(at)) {
            val duration = Duration.between(breakStartTime, at)
            return if (duration.isNegative) Duration.ZERO else duration
        }

        return Duration.ZERO
    }

    private fun pauseDurationInSeconds(profile: BlockedProfile): Duration {
        val json = profile.strategyDataJson ?: return Duration.ofMinutes(StrategyPauseTimerData.DEFAULT_PAUSE_DURATION_MINUTES.toLong())
        val pauseData = StrategyPauseTimerData.fromJsonOrNull(json)
        return Duration.ofMinutes(pauseData.pauseDurationInMinutes.toLong())
    }

    private fun isScheduledSession(session: BlockedProfileSession, profile: BlockedProfile, at: Instant): Boolean {
        val schedule = profile.schedule ?: return false
        val isActive = schedule.isActiveAt(LocalDateTime.ofInstant(at, ZoneId.systemDefault()))
        return isActive && isUuid(session.tag)
    }

    private fun isUuid(value: String): Boolean = try {
        UUID.fromString(value)
        true
    } catch (e: IllegalArgumentException) {
        false
    }
}
