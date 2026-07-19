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
        val decision = policy.decideForApp("com.android.settings", s, nowMillis = 1_000L)
        assertThat(decision).isEqualTo(BlockDecision.RedirectAway(StrictModeReason.ACCESSIBILITY_SETTINGS))
    }

    @Test
    fun `strict-mode settings redirect wins over an active pause`() {
        val s = state(strictMode = true, pauseActive = true)
        val decision = policy.decideForApp("com.android.settings", s, nowMillis = 1_000L)
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
