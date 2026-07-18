package com.foqos.android.service.accessibility

/**
 * Result of [AccessibilityBlockPolicy] deciding what should happen for a foreground app (and,
 * for browsers, a URL). Consumed by [ForegroundAppAccessibilityService] and
 * [com.foqos.android.service.session.UsageStatsPoller] to decide whether to launch the block
 * overlay or redirect the user home.
 */
sealed interface BlockDecision {

    /** Nothing to do - the foreground app/URL is not subject to blocking right now. */
    data object Allow : BlockDecision

    /** [packageName] itself is blocked - show the overlay for the whole app. */
    data class BlockApp(val packageName: String) : BlockDecision

    /** [packageName] (a browser) is allowed, but the current [domain] is blocked. */
    data class BlockDomain(val packageName: String, val domain: String) : BlockDecision

    /**
     * A strict-mode deterrent: the user opened a screen they could use to escape blocking
     * (accessibility settings, or an app uninstaller) mid-session. Redirect them home instead of
     * showing the overlay.
     */
    data class RedirectAway(val reason: StrictModeReason) : BlockDecision
}

/** Why [BlockDecision.RedirectAway] fired. */
enum class StrictModeReason {
    /** User tried to open system accessibility settings (could disable the blocking service). */
    ACCESSIBILITY_SETTINGS,

    /** User tried to open a package installer/uninstaller flow (could remove Foqos). */
    UNINSTALL,
}
