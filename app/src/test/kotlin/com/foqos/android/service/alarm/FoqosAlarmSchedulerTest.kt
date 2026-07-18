package com.foqos.android.service.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSchedule
import com.foqos.android.data.model.Weekday
import com.foqos.android.receiver.AlarmReceiver
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FoqosAlarmSchedulerTest {

    private lateinit var scheduler: FoqosAlarmScheduler
    private lateinit var alarmManager: AlarmManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scheduler = FoqosAlarmScheduler(context)
    }

    private fun scheduledKeysInAlarmManager(): List<String> {
        val shadow = Shadows.shadowOf(alarmManager)
        return shadow.scheduledAlarms.mapNotNull { alarm ->
            Shadows.shadowOf(alarm.operation).savedIntent.getStringExtra(AlarmReceiver.EXTRA_KEY)
        }
    }

    @Test
    fun `key format is typeKey colon profileId for ordinary alarm types`() {
        scheduler.scheduleExactAt(AlarmActivityType.STRATEGY_TIMER_END, "profile-1", Instant.parse("2026-01-01T10:00:00Z"))

        assertThat(scheduler.trackedKeys).containsExactly("StrategyTimerActivity:profile-1")
        assertThat(scheduledKeysInAlarmManager()).containsExactly("StrategyTimerActivity:profile-1")
    }

    @Test
    fun `grant key format includes sessionId and grantId`() {
        scheduler.scheduleExactAt(
            AlarmActivityType.GRANT_EXPIRY,
            "profile-1",
            Instant.parse("2026-01-01T10:00:00Z"),
            mapOf("sessionId" to "session-1", "grantId" to "grant-1"),
        )

        assertThat(scheduler.trackedKeys).containsExactly("SoftUnblockGrantTimerActivity:profile-1|session-1|grant-1")
    }

    @Test
    fun `scheduling the same key twice keeps a single tracked entry and updates the trigger time`() {
        scheduler.scheduleExactAt(AlarmActivityType.BREAK_END, "profile-1", Instant.parse("2026-01-01T10:00:00Z"))
        scheduler.scheduleExactAt(AlarmActivityType.BREAK_END, "profile-1", Instant.parse("2026-01-01T11:00:00Z"))

        assertThat(scheduler.trackedKeys).containsExactly("BreakScheduleActivity:profile-1")

        val shadow = Shadows.shadowOf(alarmManager)
        val alarm = shadow.scheduledAlarms.single {
            Shadows.shadowOf(it.operation).savedIntent.getStringExtra(AlarmReceiver.EXTRA_KEY) == "BreakScheduleActivity:profile-1"
        }
        assertThat(alarm.triggerAtTime).isEqualTo(Instant.parse("2026-01-01T11:00:00Z").toEpochMilli())
    }

    @Test
    fun `cancel removes only the targeted alarm`() {
        scheduler.scheduleExactAt(AlarmActivityType.BREAK_END, "profile-1", Instant.parse("2026-01-01T10:00:00Z"))
        scheduler.scheduleExactAt(AlarmActivityType.PAUSE_END, "profile-1", Instant.parse("2026-01-01T10:00:00Z"))

        scheduler.cancel(AlarmActivityType.BREAK_END, "profile-1")

        assertThat(scheduler.trackedKeys).containsExactly("PauseScheduleActivity:profile-1")
        assertThat(scheduledKeysInAlarmManager()).containsExactly("PauseScheduleActivity:profile-1")
    }

    @Test
    fun `cancelAllForProfile cancels every type including grant keys but leaves other profiles untouched`() {
        val at = Instant.parse("2026-01-01T10:00:00Z")
        scheduler.scheduleExactAt(AlarmActivityType.SCHEDULE_START, "profile-1", at)
        scheduler.scheduleExactAt(AlarmActivityType.SCHEDULE_END, "profile-1", at)
        scheduler.scheduleExactAt(AlarmActivityType.STRATEGY_TIMER_END, "profile-1", at)
        scheduler.scheduleExactAt(AlarmActivityType.BREAK_END, "profile-1", at)
        scheduler.scheduleExactAt(AlarmActivityType.PAUSE_END, "profile-1", at)
        scheduler.scheduleExactAt(
            AlarmActivityType.GRANT_EXPIRY,
            "profile-1",
            at,
            mapOf("sessionId" to "session-1", "grantId" to "grant-1"),
        )
        scheduler.scheduleExactAt(
            AlarmActivityType.GRANT_EXPIRY,
            "profile-1",
            at,
            mapOf("sessionId" to "session-1", "grantId" to "grant-2"),
        )
        // A different profile's alarm must survive the cancellation below.
        scheduler.scheduleExactAt(AlarmActivityType.BREAK_END, "profile-2", at)

        scheduler.cancelAllForProfile("profile-1")

        assertThat(scheduler.trackedKeys).containsExactly("BreakScheduleActivity:profile-2")
        assertThat(scheduledKeysInAlarmManager()).containsExactly("BreakScheduleActivity:profile-2")
    }

    @Test
    fun `scheduleWeeklySchedule arms both a start and an end alarm from the schedule`() {
        val schedule = BlockedProfileSchedule(
            days = listOf(Weekday.MONDAY),
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
        )
        val profile = BlockedProfile(id = "profile-1", name = "Focus", schedule = schedule)

        scheduler.scheduleWeeklySchedule(profile)

        assertThat(scheduler.trackedKeys).containsExactly(
            "ScheduleTimerActivityStart:profile-1",
            "ScheduleTimerActivityEnd:profile-1",
        )
    }

    @Test
    fun `rearmScheduleStart only touches the start key`() {
        val schedule = BlockedProfileSchedule(
            days = listOf(Weekday.MONDAY, Weekday.WEDNESDAY),
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
        )
        val profile = BlockedProfile(id = "profile-1", name = "Focus", schedule = schedule)
        scheduler.scheduleWeeklySchedule(profile)
        val shadow = Shadows.shadowOf(alarmManager)
        val endTriggerBefore = shadow.scheduledAlarms.single {
            Shadows.shadowOf(it.operation).savedIntent.getStringExtra(AlarmReceiver.EXTRA_KEY) == "ScheduleTimerActivityEnd:profile-1"
        }.triggerAtTime

        scheduler.rearmScheduleStart(profile)

        assertThat(scheduler.trackedKeys).containsExactly(
            "ScheduleTimerActivityStart:profile-1",
            "ScheduleTimerActivityEnd:profile-1",
        )
        // The end alarm scheduled by scheduleWeeklySchedule must not have been touched (same trigger time).
        val endTriggerAfter = shadow.scheduledAlarms.single {
            Shadows.shadowOf(it.operation).savedIntent.getStringExtra(AlarmReceiver.EXTRA_KEY) == "ScheduleTimerActivityEnd:profile-1"
        }.triggerAtTime
        assertThat(endTriggerAfter).isEqualTo(endTriggerBefore)
    }
}
