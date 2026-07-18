package com.foqos.android.domain.strategy

/**
 * Groups shown in the strategy picker UI, mirroring iOS `BlockingStrategyPickerCategory`.
 */
enum class StrategyCategory(val title: String, val description: String) {
    MOST_POPULAR(
        title = "Most popular",
        description = "Physical triggers that make starting and stopping more deliberate.",
    ),
    EASY_TO_START(
        title = "Easy to start",
        description = "Start from the app, then choose how intentional stopping should be.",
    ),
    TIMERS(
        title = "Timers",
        description = "Choose a duration first, then let the session end automatically.",
    ),
    FOREVER(
        title = "Forever",
        description = "Sessions that keep going until you intentionally stop.",
    ),
    MORE_OPTIONS(
        title = "More options",
        description = "Additional ways to control a focus session.",
    ),
}
