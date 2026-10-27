package com.foqos.android.service.accessibility

import com.foqos.android.service.session.BlockingState
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure decision engine for "what should happen for this foreground app/URL right now", mirroring
 * iOS's `ManagedSettings`-driven shielding logic. Has **no Android framework dependency** -
 * [ForegroundAppAccessibilityService] and
 * [com.foqos.android.service.session.UsageStatsPoller] are the only callers, and both hand it a
 * plain [BlockingState] snapshot plus the resolved package/URL. This keeps the actual blocking
 * *decision* fully unit-testable without Robolectric.
 *
 * Priority order (first match wins) - see each `decideForApp`/`decideForUrl` step below:
 *  1. No active session ([state] is null) -> [BlockDecision.Allow].
 *  2. [neverBlockPackages] (Foqos itself, System UI, the current launcher, dialer/phone/telecom) ->
 *     always [BlockDecision.Allow], even mid-session.
 *  3. In strict mode, only Settings screens identified as Foqos protection targets are
 *     redirected away. Settings without screen-level evidence remain available.
 *  4. A package installer/uninstaller while [BlockingState.blockAppInstallation] is on ->
 *     [BlockDecision.RedirectAway] (also survives break/pause, same reasoning as strict mode).
 *  5. [BlockingState.breakActive] or [BlockingState.pauseActive] -> [BlockDecision.Allow] for
 *     everything else.
 *  6. An unexpired soft-unblock grant for the package -> [BlockDecision.Allow].
 *  7. Allow-list mode: block unless the package is in [BlockingState.blockedPackages]. Block-list
 *     mode: block only if it is.
 *
 * [decideForUrl] first runs the exact same app-level logic (so a browser that is itself in the
 * blocked-apps set is blocked outright, without inspecting its URL), and only inspects the URL's
 * host if that comes back [BlockDecision.Allow].
 */
@Singleton
class AccessibilityBlockPolicy @Inject constructor() {

    /** Decides what should happen for [packageName] being in the foreground right now. */
    fun decideForApp(
        packageName: String,
        state: BlockingState?,
        nowMillis: Long = System.currentTimeMillis(),
        neverBlockPackages: Set<String> = DEFAULT_NEVER_BLOCK,
        protectedSettingsScreen: Boolean = false,
    ): BlockDecision {
        if (state == null) return BlockDecision.Allow
        if (packageName in neverBlockPackages) return BlockDecision.Allow

        if (packageName in SETTINGS_PACKAGES) {
            return if (state.strictMode && protectedSettingsScreen) {
                BlockDecision.RedirectAway(StrictModeReason.ACCESSIBILITY_SETTINGS)
            } else {
                BlockDecision.Allow
            }
        }
        if (state.blockAppInstallation && packageName in PACKAGE_INSTALLER_PACKAGES) {
            return BlockDecision.RedirectAway(StrictModeReason.UNINSTALL)
        }

        if (state.breakActive || state.pauseActive) return BlockDecision.Allow

        val grantExpiry = state.activeGrants[packageName]
        if (grantExpiry != null && grantExpiry > nowMillis) return BlockDecision.Allow

        val isSelected = packageName in state.blockedPackages
        val shouldBlock = if (state.allowMode) !isSelected else isSelected
        return if (shouldBlock) BlockDecision.BlockApp(packageName) else BlockDecision.Allow
    }

    /**
     * Decides what should happen for [url] currently shown in [packageName] (a browser). Only
     * meaningful for [BROWSER_PACKAGES] - callers are expected to only invoke this for those.
     */
    fun decideForUrl(
        packageName: String,
        url: String?,
        state: BlockingState?,
        nowMillis: Long = System.currentTimeMillis(),
        neverBlockPackages: Set<String> = DEFAULT_NEVER_BLOCK,
    ): BlockDecision {
        val appDecision = decideForApp(packageName, state, nowMillis, neverBlockPackages)
        if (appDecision !is BlockDecision.Allow) return appDecision
        // App-level pause/break must also suspend URL filtering.
        if (state?.pauseActive == true || state?.breakActive == true) return BlockDecision.Allow
        if (state == null || state.blockedDomains.isEmpty() || url.isNullOrBlank()) return BlockDecision.Allow

        val host = extractHost(url) ?: return BlockDecision.Allow
        val matchesList = state.blockedDomains.any { pattern -> hostMatchesDomain(host, pattern) }
        val shouldBlock = if (state.allowModeDomains) !matchesList else matchesList
        return if (shouldBlock) BlockDecision.BlockDomain(packageName, host) else BlockDecision.Allow
    }

    companion object {
        /** Foqos's own applicationId - never shield ourselves. */
        const val FOQOS_PACKAGE_NAME = "com.foqos.android"

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        val DIALER_PACKAGES: Set<String> = setOf(
            "com.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
            "com.google.android.dialer",
        )

        /** Never-block set with no launcher resolved - callers should add the current launcher's package. */
        val DEFAULT_NEVER_BLOCK: Set<String> = setOf(FOQOS_PACKAGE_NAME, SYSTEM_UI_PACKAGE) + DIALER_PACKAGES

        val SETTINGS_PACKAGES: Set<String> = setOf(
            "com.android.settings",
            // Samsung/One UI has shipped the Settings app under more than one package name across
            // versions and shipped SKUs - keep every variant we've seen so strict mode's
            // Settings redirect can't be bypassed by falling through an unrecognized alias.
            "com.samsung.android.app.settings",
            "com.samsung.android.settings",
            "com.google.android.settings",
        )

        val PACKAGE_INSTALLER_PACKAGES: Set<String> = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
        )

        /**
         * Common OEM launcher packages, always exempt in addition to whatever
         * [ForegroundAppAccessibilityService.currentLauncherPackageName] resolves at runtime - a
         * defensive fallback for cases where multiple home apps are installed and
         * `resolveActivity` returns a chooser/resolver activity instead of the actual launcher
         * (or returns null). Must include Samsung/One UI's launcher package.
         */
        val LAUNCHER_PACKAGES: Set<String> = setOf(
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
        )

        /** Browsers whose address bar [ForegroundAppAccessibilityService] knows how to read. */
        val BROWSER_PACKAGES: Set<String> = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "org.chromium.chrome",
        )

        /**
         * Builds [DEFAULT_NEVER_BLOCK] plus [LAUNCHER_PACKAGES] plus [launcherPackageName] (the
         * dynamically-resolved current launcher, if any), for callers that resolved it.
         */
        fun neverBlockWithLauncher(launcherPackageName: String?): Set<String> {
            val base = DEFAULT_NEVER_BLOCK + LAUNCHER_PACKAGES
            return if (launcherPackageName.isNullOrBlank()) base else base + launcherPackageName
        }

        /** Whether [host] is covered by [domainPattern] (exact match, or any subdomain of it). */
        internal fun hostMatchesDomain(host: String, domainPattern: String): Boolean {
            val normalizedPattern = domainPattern.trim().removePrefix("www.").lowercase()
            if (normalizedPattern.isEmpty()) return false
            return host == normalizedPattern || host.endsWith(".$normalizedPattern")
        }

        /**
         * Extracts and normalizes (lowercase, `www.` stripped) the host from [rawUrl], which may be
         * a full URL, a bare host, or free-form text read from a URL bar. Returns null if no host
         * can be determined at all.
         */
        internal fun extractHost(rawUrl: String): String? {
            // Some browsers (notably Samsung Internet) prefix the address-bar text with invisible
            // bidi/directionality control characters; strip all Unicode format chars (category Cf)
            // and surrounding whitespace before parsing, or URI parsing silently yields a null host.
            val trimmed = rawUrl.filterNot { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() }
            if (trimmed.isEmpty()) return null

            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            val host = runCatching { URI(withScheme).host }.getOrNull()
                ?: trimmed.substringBefore('/').substringBefore('?').substringBefore(':').takeIf { it.isNotBlank() }

            return host?.trim()?.removePrefix("www.")?.lowercase()?.takeIf { it.isNotEmpty() }
        }
    }
}
