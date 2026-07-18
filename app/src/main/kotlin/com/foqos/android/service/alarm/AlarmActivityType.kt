package com.foqos.android.service.alarm

/**
 * The kinds of one-shot alarms [FoqosAlarmScheduler] schedules, mirroring iOS's DeviceActivity
 * naming (the `TimerActivity` implementations under `Foqos/Models/Timers`) so the two platforms
 * stay easy to cross-reference. iOS
 * uses one continuously-monitored "activity" per concern that fires both a start and an end
 * callback; Android's [android.app.AlarmManager] has no such interval concept, so schedule
 * start/end are modeled as two distinct one-shot alarms here - both still keyed off the iOS
 * `ScheduleTimerActivity` name via [key].
 *
 * [EMERGENCY_ALLOWANCE_RESET] and [REMINDER] have no direct iOS DeviceActivity equivalent (iOS
 * handles the emergency-unblock reset via opportunistic foreground checks and reminders via a
 * plain local notification timer) - they are modeled as alarms here so they still fire reliably
 * while the app is not in the foreground.
 */
enum class AlarmActivityType(val key: String) {
    SCHEDULE_START("ScheduleTimerActivityStart"),
    SCHEDULE_END("ScheduleTimerActivityEnd"),
    STRATEGY_TIMER_END("StrategyTimerActivity"),
    BREAK_END("BreakScheduleActivity"),
    PAUSE_END("PauseScheduleActivity"),
    GRANT_EXPIRY("SoftUnblockGrantTimerActivity"),
    EMERGENCY_ALLOWANCE_RESET("EmergencyAllowanceResetActivity"),
    REMINDER("ReminderActivity"),
}
