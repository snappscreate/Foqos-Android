package com.foqos.android.service.accessibility

import com.foqos.android.service.session.BlockingState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Exhaustive coverage of [AccessibilityBlockPolicy]'s priority order. Pure JUnit - the policy has
 * no Android framework dependency, so nothing here needs Robolectric.
 */
class AccessibilityBlockPolicyTest {

    private val policy = AccessibilityBlockPolicy()

    private fun state(
        blockedPackages: Set<String> = emptySet(),
        allowMode: Boolean = false,
        blockedDomains: List<String> = emptyList(),
        allowModeDomains: Boolean = false,
        strictMode: Boolean = false,
        blockAppInstallation: Boolean = false,
        breakActive: Boolean = false,
        pauseActive: Boolean = false,
        activeGrants: Map<String, Long> = emptyMap(),
    ) = BlockingState(
        profileId = "profile-1",
        sessionId = "session-1",
        profileName = "Test profile",
        blockedPackages = blockedPackages,
        allowMode = allowMode,
        blockedDomains = blockedDomains,
        allowModeDomains = allowModeDomains,
        strictMode = strictMode,
        blockAppInstallation = blockAppInstallation,
        breakActive = breakActive,
        pauseActive = pauseActive,
        activeGrants = activeGrants,
        sessionStartEpochMillis = 0L,
    )

    @Test fun `multiple profiles enforce explicit block over another profile allowlist`() {
        val allowlist = state().copy(
            profileId = "allow", sessionId = "s-allow",
            fullScreenMode = true,
            fullScreenAllowedPackages = setOf("com.example.video"),
        )
        val blocklist = state(blockedPackages = setOf("com.example.video")).copy(
            profileId = "block", sessionId = "s-block",
        )
        assertThat(policy.decideForAppAcrossProfiles(
            "com.example.video", listOf(allowlist, blocklist),
        )).isEqualTo(BlockDecision.BlockApp("com.example.video"))
    }

    @Test fun `a pause in one profile does not release another profile block`() {
        val paused = state().copy(
            profileId = "paused", sessionId = "s-paused",
            fullScreenMode = true, pauseActive = true,
        )
        val blocked = state(blockedPackages = setOf("com.example.video")).copy(
            profileId = "blocked", sessionId = "s-blocked",
        )
        assertThat(policy.decideForAppAcrossProfiles(
            "com.example.video", listOf(paused, blocked),
        )).isEqualTo(BlockDecision.BlockApp("com.example.video"))
    }

    @Test fun `two independently allowing profiles permit an app`() {
        val one = state().copy(profileId = "one", sessionId = "s-one",
            fullScreenMode = true, fullScreenAllowedPackages = setOf("com.example.reader"))
        val two = state().copy(profileId = "two", sessionId = "s-two",
            fullScreenMode = true, fullScreenAllowedPackages = setOf("com.example.reader"))
        assertThat(policy.decideForAppAcrossProfiles(
            "com.example.reader", listOf(one, two),
        )).isEqualTo(BlockDecision.Allow)
    }

    @Test fun `one profile can still block a URL allowed by another`() {
        val allowed = state().copy(profileId = "one", sessionId = "s-one",
            fullScreenMode = true, fullScreenAllowedPackages = setOf("com.android.chrome"))
        val blocked = state(
            blockedDomains = listOf("example.com"),
        ).copy(profileId = "two", sessionId = "s-two")
        assertThat(policy.decideForUrlAcrossProfiles(
            "com.android.chrome", "https://example.com", listOf(allowed, blocked),
        )).isEqualTo(BlockDecision.BlockDomain("com.android.chrome", "example.com"))
    }

    @Test fun `geofence suspension of one profile does not suspend another`() {
        val outside = state().copy(profileId = "outside", sessionId = "s-outside",
            fullScreenMode = true, locationSuspended = true)
        val inside = state(blockedPackages = setOf("com.example.video"))
            .copy(profileId = "inside", sessionId = "s-inside")
        assertThat(policy.decideForAppAcrossProfiles(
            "com.example.video", listOf(outside, inside),
        )).isEqualTo(BlockDecision.BlockApp("com.example.video"))
    }

    // --- No active session ---------------------------------------------------------------

    @Test
    fun `null state always allows`() {
        val decision = policy.decideForApp("com.example.app", state = null, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    // --- neverBlock exemptions -------------------------------------------------------------

    @Test
    fun `Foqos itself is never blocked`() {
        val s = state(blockedPackages = setOf(AccessibilityBlockPolicy.FOQOS_PACKAGE_NAME))
        val decision = policy.decideForApp(AccessibilityBlockPolicy.FOQOS_PACKAGE_NAME, s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `system UI is never blocked`() {
        val s = state(allowMode = true) // allow-mode blocks everything not selected
        val decision = policy.decideForApp("com.android.systemui", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `current launcher is never blocked when passed in`() {
        val s = state(allowMode = true)
        val neverBlock = AccessibilityBlockPolicy.neverBlockWithLauncher("com.example.launcher")
        val decision = policy.decideForApp("com.example.launcher", s, nowMillis = 1_000L, neverBlockPackages = neverBlock)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `dialer and phone packages are never blocked`() {
        val s = state(allowMode = true)
        assertThat(policy.decideForApp("com.android.dialer", s, nowMillis = 1_000L)).isEqualTo(BlockDecision.Allow)
        assertThat(policy.decideForApp("com.android.phone", s, nowMillis = 1_000L)).isEqualTo(BlockDecision.Allow)
        assertThat(policy.decideForApp("com.samsung.android.dialer", s, nowMillis = 1_000L)).isEqualTo(BlockDecision.Allow)
    }

    // --- Full-screen focus mode ----------------------------------------------------------

    @Test fun `fullscreen mode blocks launcher even when normally exempt`() {
        val s = state().copy(fullScreenMode = true, fullScreenAllowedPackages = setOf("com.example.allowed"))
        val exemptions = AccessibilityBlockPolicy.neverBlockWithLauncher("com.example.launcher")
        assertThat(policy.decideForApp("com.example.launcher", s, neverBlockPackages = exemptions))
            .isEqualTo(BlockDecision.BlockApp("com.example.launcher"))
    }

    @Test fun `fullscreen mode permits selected apps and Android settings`() {
        val s = state().copy(fullScreenMode = true, fullScreenAllowedPackages = setOf("com.example.allowed"))
        assertThat(policy.decideForApp("com.example.allowed", s)).isEqualTo(BlockDecision.Allow)
        assertThat(policy.decideForApp("com.android.settings", s)).isEqualTo(BlockDecision.Allow)
        assertThat(policy.decideForApp("com.example.unlisted", s))
            .isEqualTo(BlockDecision.BlockApp("com.example.unlisted"))
    }

    @Test fun `fullscreen mode keeps emergency apps reachable and allows pause`() {
        val s = state().copy(fullScreenMode = true)
        assertThat(policy.decideForApp("com.android.dialer", s)).isEqualTo(BlockDecision.Allow)
        assertThat(policy.decideForApp("com.android.systemui", s)).isEqualTo(BlockDecision.Allow)
        assertThat(policy.decideForApp("com.example.unlisted", s.copy(pauseActive = true)))
            .isEqualTo(BlockDecision.Allow)
    }

    @Test fun `fullscreen website only permits approved host in default browser`() {
        val s = state().copy(
            fullScreenMode = true,
            fullScreenAllowedUrls = setOf("https://example.com"),
            fullScreenBrowserPackage = "com.android.chrome",
        )
        assertThat(policy.decideForUrl(
            "com.android.chrome", "https://sub.example.com/read", s
        )).isEqualTo(BlockDecision.Allow)
        assertThat(policy.decideForUrl(
            "com.android.chrome", "https://youtube.com", s
        )).isEqualTo(BlockDecision.BlockDomain("com.android.chrome", "youtube.com"))
    }

    @Test fun `fullscreen browser startup grace is temporary`() {
        val s = state().copy(
            fullScreenMode = true,
            fullScreenAllowedUrls = setOf("https://example.com"),
            fullScreenBrowserPackage = "com.android.chrome",
            fullScreenBrowserStartupUntil = 4_000L,
        )
        assertThat(policy.decideForApp("com.android.chrome", s, nowMillis = 3_000L))
            .isEqualTo(BlockDecision.Allow)
        assertThat(policy.decideForApp("com.android.chrome", s, nowMillis = 5_000L))
            .isEqualTo(BlockDecision.BlockApp("com.android.chrome"))
    }

    @Test fun `fullscreen browser can navigate during active pause`() {
        val s = state().copy(
            fullScreenMode = true,
            fullScreenAllowedUrls = setOf("https://example.com"),
            fullScreenBrowserPackage = "com.android.chrome",
            pauseActive = true,
        )
        assertThat(policy.decideForUrl("com.android.chrome", "https://youtube.com", s))
            .isEqualTo(BlockDecision.Allow)
    }

    @Test fun `fullscreen browser tolerates old tab during approved URL launch`() {
        val state = state().copy(
            fullScreenMode = true,
            fullScreenAllowedUrls = setOf("https://example.com"),
            fullScreenBrowserPackage = "com.android.chrome",
            fullScreenBrowserStartupUntil = 12_000L,
        )
        assertThat(policy.decideForUrl(
            "com.android.chrome", "https://previous-site.com", state, nowMillis = 3_000L
        )).isEqualTo(BlockDecision.Allow)
        assertThat(policy.decideForUrl(
            "com.android.chrome", "https://previous-site.com", state, nowMillis = 13_000L
        )).isEqualTo(BlockDecision.BlockDomain("com.android.chrome", "previous-site.com"))
    }

    @Test fun `geofence suspension allows everything until reentry`() {
        val suspended = state().copy(
            fullScreenMode = true, locationSuspended = true
        )
        assertThat(policy.decideForApp("com.example.blocked", suspended))
            .isEqualTo(BlockDecision.Allow)
        assertThat(policy.decideForUrl("com.android.chrome", "https://blocked.com", suspended))
            .isEqualTo(BlockDecision.Allow)
        assertThat(policy.decideForApp("com.example.blocked",
            suspended.copy(locationSuspended = false)))
            .isEqualTo(BlockDecision.BlockApp("com.example.blocked"))
    }

    @Test fun `periodic break must be manually activated before bypassing fullscreen focus`() {
        val anchor = 10_000L
        val activated = anchor + 60 * 60_000L
        val base = state().copy(
            fullScreenMode = true,
            periodicWaitMinutes = 60,
            periodicAccessMinutes = 5,
            periodicAnchorEpochMillis = anchor,
        )
        val app = "com.example.blocked"
        assertThat(policy.decideForApp(app, base, nowMillis = activated + 1_000L))
            .isEqualTo(BlockDecision.BlockApp(app))
        val granted = base.copy(periodicActivatedAtEpochMillis = activated)
        assertThat(policy.decideForApp(app, granted, nowMillis = activated + 1_000L))
            .isEqualTo(BlockDecision.Allow)
        assertThat(policy.decideForApp(app, granted, nowMillis = activated + 5 * 60_000L))
            .isEqualTo(BlockDecision.BlockApp(app))
    }

    // --- Block-list / allow-list -------------------------------------------------------------

    @Test
    fun `block-list mode blocks a listed package`() {
        val s = state(blockedPackages = setOf("com.instagram.android"))
        val decision = policy.decideForApp("com.instagram.android", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.BlockApp("com.instagram.android"))
    }

    @Test
    fun `block-list mode allows an unlisted package`() {
        val s = state(blockedPackages = setOf("com.instagram.android"))
        val decision = policy.decideForApp("com.spotify.music", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `allow-list mode blocks a package not in the selected set`() {
        val s = state(blockedPackages = setOf("com.company.workapp"), allowMode = true)
        val decision = policy.decideForApp("com.instagram.android", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.BlockApp("com.instagram.android"))
    }

    @Test
    fun `allow-list mode allows a package in the selected set`() {
        val s = state(blockedPackages = setOf("com.company.workapp"), allowMode = true)
        val decision = policy.decideForApp("com.company.workapp", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    // --- Break / pause -------------------------------------------------------------------

    @Test
    fun `break active allows a normally blocked package`() {
        val s = state(blockedPackages = setOf("com.instagram.android"), breakActive = true)
        val decision = policy.decideForApp("com.instagram.android", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `pause active allows a normally blocked package`() {
        val s = state(blockedPackages = setOf("com.instagram.android"), pauseActive = true)
        val decision = policy.decideForApp("com.instagram.android", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `strict-mode settings redirect wins over an active break`() {
        val s = state(strictMode = true, breakActive = true)
        val decision = policy.decideForApp("com.android.settings", s, nowMillis = 1_000L, protectedSettingsScreen = true)
        assertThat(decision).isEqualTo(BlockDecision.RedirectAway(StrictModeReason.ACCESSIBILITY_SETTINGS))
    }

    @Test
    fun `strict-mode settings redirect wins over an active pause`() {
        val s = state(strictMode = true, pauseActive = true)
        val decision = policy.decideForApp("com.android.settings", s, nowMillis = 1_000L, protectedSettingsScreen = true)
        assertThat(decision).isEqualTo(BlockDecision.RedirectAway(StrictModeReason.ACCESSIBILITY_SETTINGS))
    }

    @Test
    fun `settings is allowed when strict mode is off`() {
        val s = state(strictMode = false)
        val decision = policy.decideForApp("com.android.settings", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `uninstall interception wins over an active break`() {
        val s = state(blockAppInstallation = true, breakActive = true)
        val decision = policy.decideForApp("com.android.packageinstaller", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.RedirectAway(StrictModeReason.UNINSTALL))
    }

    // --- Grants -------------------------------------------------------------------------

    @Test
    fun `active unexpired grant allows the package`() {
        val s = state(blockedPackages = setOf("com.instagram.android"), activeGrants = mapOf("com.instagram.android" to 5_000L))
        val decision = policy.decideForApp("com.instagram.android", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `expired grant does not allow the package`() {
        val s = state(blockedPackages = setOf("com.instagram.android"), activeGrants = mapOf("com.instagram.android" to 500L))
        val decision = policy.decideForApp("com.instagram.android", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.BlockApp("com.instagram.android"))
    }

    // --- Uninstall interception -----------------------------------------------------------

    @Test
    fun `package installer is intercepted when blockAppInstallation is on`() {
        val s = state(blockAppInstallation = true)
        assertThat(policy.decideForApp("com.android.packageinstaller", s, nowMillis = 1_000L))
            .isEqualTo(BlockDecision.RedirectAway(StrictModeReason.UNINSTALL))
        assertThat(policy.decideForApp("com.google.android.packageinstaller", s, nowMillis = 1_000L))
            .isEqualTo(BlockDecision.RedirectAway(StrictModeReason.UNINSTALL))
    }

    @Test
    fun `package installer is allowed when blockAppInstallation is off`() {
        val s = state(blockAppInstallation = false)
        val decision = policy.decideForApp("com.android.packageinstaller", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    // --- URL / domain matching --------------------------------------------------------------

    @Test
    fun `blocked domain suffix matches a subdomain`() {
        val s = state(blockedDomains = listOf("youtube.com"))
        val decision = policy.decideForUrl("com.android.chrome", "https://m.youtube.com/watch?v=1", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.BlockDomain("com.android.chrome", "m.youtube.com"))
    }

    @Test
    fun `blocked domain does not match an unrelated domain with a similar suffix`() {
        val s = state(blockedDomains = listOf("youtube.com"))
        val decision = policy.decideForUrl("com.android.chrome", "https://notyoutube.com", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `www is stripped from both the host and the configured pattern`() {
        val s = state(blockedDomains = listOf("www.youtube.com"))
        val decision = policy.decideForUrl("com.android.chrome", "https://www.youtube.com/", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.BlockDomain("com.android.chrome", "youtube.com"))
    }

    @Test
    fun `bare host with a leading bidi control char from Samsung Internet still blocks`() {
        // Samsung Internet's address bar reports its text as a scheme-less host prefixed with a
        // U+200E LEFT-TO-RIGHT MARK; both quirks must be tolerated or the domain never matches.
        val s = state(blockedDomains = listOf("youtube.com"))
        val decision = policy.decideForUrl("com.sec.android.app.sbrowser", "‎m.youtube.com", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.BlockDomain("com.sec.android.app.sbrowser", "m.youtube.com"))
    }

    @Test
    fun `paused session allows blocked domains`() {
        val s = state(blockedDomains = listOf("youtube.com"), pauseActive = true)
        assertThat(policy.decideForUrl("com.android.chrome", "https://youtube.com", s, nowMillis = 1_000L))
            .isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `timed break allows blocked domains`() {
        val s = state(blockedDomains = listOf("youtube.com"), breakActive = true)
        assertThat(policy.decideForUrl("com.android.chrome", "https://youtube.com", s, nowMillis = 1_000L))
            .isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `allowModeDomains blocks a domain not in the list`() {
        val s = state(blockedDomains = listOf("docs.google.com"), allowModeDomains = true)
        val decision = policy.decideForUrl("com.android.chrome", "https://youtube.com", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.BlockDomain("com.android.chrome", "youtube.com"))
    }

    @Test
    fun `allowModeDomains allows a domain in the list`() {
        val s = state(blockedDomains = listOf("docs.google.com"), allowModeDomains = true)
        val decision = policy.decideForUrl("com.android.chrome", "https://docs.google.com/document/1", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `browser blocked as an app wins over URL inspection`() {
        val s = state(blockedPackages = setOf("com.android.chrome"), blockedDomains = listOf("docs.google.com"))
        val decision = policy.decideForUrl("com.android.chrome", "https://a-totally-fine-site.com", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.BlockApp("com.android.chrome"))
    }

    @Test
    fun `no domain list configured allows all URLs`() {
        val s = state(blockedDomains = emptyList())
        val decision = policy.decideForUrl("com.android.chrome", "https://youtube.com", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }

    @Test
    fun `null url is allowed`() {
        val s = state(blockedDomains = listOf("youtube.com"))
        val decision = policy.decideForUrl("com.android.chrome", null, s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.Allow)
    }
}
