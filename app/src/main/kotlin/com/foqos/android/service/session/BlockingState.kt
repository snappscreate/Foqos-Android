package com.foqos.android.service.session

/**
 * Immutable snapshot of "what should currently be blocked", published by [SessionManager] (via
 * [BlockingStateHolder]) whenever a session starts, ends, or changes (break/pause/grant). Consumed
 * by the accessibility service / overlay (Phase 5) to make pure allow/block decisions - this class
 * itself has no Android framework dependencies.
 *
 * @param activeGrants Soft-unblock grants currently open, keyed by package name, value is the
 * grant's expiry instant as epoch millis.
 */
data class BlockingState(
    val profileId: String,
    val sessionId: String,
    val profileName: String,
    val blockedPackages: Set<String> = emptySet(),
    val allowMode: Boolean = false,
    val fullScreenMode: Boolean = false,
    val fullScreenAllowedPackages: Set<String> = emptySet(),
    val fullScreenAllowedUrls: Set<String> = emptySet(),
    val fullScreenBrowserPackage: String? = null,
    val fullScreenBrowserStartupUntil: Long = 0L,
    val blockedDomains: List<String> = emptyList(),
    val allowModeDomains: Boolean = false,
    val strictMode: Boolean = false,
    val blockAppInstallation: Boolean = false,
    val breakActive: Boolean = false,
    val pauseActive: Boolean = false,
    val periodicAccessActive: Boolean = false,
    val periodicWaitMinutes: Int = 0,
    val periodicAccessMinutes: Int = 0,
    val periodicAnchorEpochMillis: Long = 0L,
    val locationSuspended: Boolean = false,
    val activeGrants: Map<String, Long> = emptyMap(),
    val disableBackgroundStops: Boolean = false,
    val sessionStartEpochMillis: Long = 0L,
)
