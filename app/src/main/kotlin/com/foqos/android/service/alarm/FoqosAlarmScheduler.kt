package com.foqos.android.service.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.domain.schedule.ScheduleCalculator
import com.foqos.android.receiver.AlarmReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [AlarmManager] scheduling every one-shot alarm [SessionManager] needs
 * (schedule start/end, strategy timers, break/pause end, soft-unblock grant expiry, emergency
 * allowance reset, reminders). Mirrors iOS's `DeviceActivityCenterUtil`, but since Android has no
 * continuously-monitored "activity" concept, every concern is scheduled as an explicit one-shot
 * alarm that [com.foqos.android.receiver.AlarmReceiver] re-arms (if needed) once it fires.
 *
 * Key format: `"{typeKey}:{profileId}"`, or for soft-unblock grants (which can have several active
 * at once per profile) `"{typeKey}:{profileId}|{sessionId}|{grantId}"`. The [PendingIntent] request
 * code is the key's stable [String.hashCode] (deterministic per the JDK's documented algorithm),
 * so re-scheduling the same key updates the existing alarm in place.
 */
@Singleton
class FoqosAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Keys of every alarm currently believed to be scheduled - lets [cancelAllForProfile] work. */
    private val scheduledKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** All keys currently tracked as scheduled - exposed for tests/debugging only. */
    val trackedKeys: Set<String> get() = scheduledKeys.toSet()

    /**
     * Schedules an exact alarm of [type] for [profileId] at [at]. Falls back to
     * [AlarmManager.setAndAllowWhileIdle] when exact alarms cannot be scheduled (no
     * `SCHEDULE_EXACT_ALARM` permission on API 31+).
     */
    fun scheduleExactAt(
        type: AlarmActivityType,
        profileId: String,
        at: Instant,
        extras: Map<String, String> = emptyMap(),
    ) {
        val key = buildKey(type, profileId, extras)
        val pendingIntent = buildPendingIntent(type, profileId, key, extras)
        val triggerAtMillis = at.toEpochMilli()

        if (canScheduleExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } catch (e: SecurityException) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }

        scheduledKeys.add(key)
    }

    /** Schedules an inexact-ok alarm (e.g. [AlarmActivityType.REMINDER]) via `setAndAllowWhileIdle`. */
    fun scheduleWhileIdleAt(
        type: AlarmActivityType,
        profileId: String,
        at: Instant,
        extras: Map<String, String> = emptyMap(),
    ) {
        val key = buildKey(type, profileId, extras)
        val pendingIntent = buildPendingIntent(type, profileId, key, extras)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pendingIntent)
        scheduledKeys.add(key)
    }

    /** Cancels the single alarm identified by [type]/[profileId]/[extras], if any is scheduled. */
    fun cancel(type: AlarmActivityType, profileId: String, extras: Map<String, String> = emptyMap()) {
        cancelKey(buildKey(type, profileId, extras))
    }

    /** Cancels every alarm currently tracked for [profileId], across all [AlarmActivityType]s. */
    fun cancelAllForProfile(profileId: String) {
        scheduledKeys.filter { keyBelongsToProfile(it, profileId) }.forEach { cancelKey(it) }
    }

    /**
     * (Re)schedules only the next [AlarmActivityType.SCHEDULE_START] occurrence for [profile]'s
     * schedule, leaving any already-armed [AlarmActivityType.SCHEDULE_END] untouched - used right
     * after a SCHEDULE_START alarm fires, since that alarm's matching SCHEDULE_END for the current
     * occurrence is still pending and must not be disturbed.
     */
    fun rearmScheduleStart(profile: BlockedProfile) {
        val schedule = profile.schedule ?: return
        cancel(AlarmActivityType.SCHEDULE_START, profile.id)
        if (!schedule.isConfigured) return

        val (nextStart, _) = ScheduleCalculator.nextOccurrences(schedule, Instant.now())
        nextStart?.let { scheduleExactAt(AlarmActivityType.SCHEDULE_START, profile.id, it) }
    }

    /**
     * (Re)schedules both the next [AlarmActivityType.SCHEDULE_START] and
     * [AlarmActivityType.SCHEDULE_END] occurrences for [profile]'s schedule, cancelling any
     * previously-armed schedule alarms first. Safe to call any time "now" is outside the current
     * occurrence's window (e.g. on boot, after a profile's schedule is edited, or after a
     * schedule-driven session has fully ended).
     */
    fun scheduleWeeklySchedule(profile: BlockedProfile) {
        val schedule = profile.schedule
        cancel(AlarmActivityType.SCHEDULE_START, profile.id)
        cancel(AlarmActivityType.SCHEDULE_END, profile.id)
        if (schedule == null || !schedule.isConfigured) return

        val (nextStart, nextEnd) = ScheduleCalculator.nextOccurrences(schedule, Instant.now())
        nextStart?.let { scheduleExactAt(AlarmActivityType.SCHEDULE_START, profile.id, it) }
        nextEnd?.let { scheduleExactAt(AlarmActivityType.SCHEDULE_END, profile.id, it) }
    }

    private fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager.canScheduleExactAlarms()
    }

    private fun cancelKey(key: String) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            buildIntent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        scheduledKeys.remove(key)
    }

    private fun buildKey(type: AlarmActivityType, profileId: String, extras: Map<String, String>): String {
        val base = "${type.key}:$profileId"
        if (type != AlarmActivityType.GRANT_EXPIRY) return base

        val sessionId = extras[AlarmReceiver.EXTRA_SESSION_ID] ?: extras["sessionId"] ?: ""
        val grantId = extras[AlarmReceiver.EXTRA_GRANT_ID] ?: extras["grantId"] ?: ""
        return "$base|$sessionId|$grantId"
    }

    private fun keyBelongsToProfile(key: String, profileId: String): Boolean {
        val afterFirstColon = key.substringAfter(':', missingDelimiterValue = "")
        val idPart = afterFirstColon.substringBefore('|')
        return idPart == profileId
    }

    private fun buildIntent(): Intent = Intent(context, AlarmReceiver::class.java)

    private fun buildPendingIntent(
        type: AlarmActivityType,
        profileId: String,
        key: String,
        extras: Map<String, String>,
    ): PendingIntent {
        val intent = buildIntent().apply {
            putExtra(AlarmReceiver.EXTRA_TYPE, type.name)
            putExtra(AlarmReceiver.EXTRA_PROFILE_ID, profileId)
            putExtra(AlarmReceiver.EXTRA_KEY, key)
            extras["sessionId"]?.let { putExtra(AlarmReceiver.EXTRA_SESSION_ID, it) }
            extras["grantId"]?.let { putExtra(AlarmReceiver.EXTRA_GRANT_ID, it) }
            extras["packageName"]?.let { putExtra(AlarmReceiver.EXTRA_PACKAGE_NAME, it) }
        }
        return PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
