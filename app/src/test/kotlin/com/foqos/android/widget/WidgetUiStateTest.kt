package com.foqos.android.widget

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

/** Pure-logic coverage for [resolveWidgetState] - no Glance/Android types involved. */
class WidgetUiStateTest {

    private fun profile(id: String, name: String = "Profile $id", disableBackgroundStops: Boolean = false) = BlockedProfile(
        id = id,
        name = name,
        blockedPackages = listOf("com.a", "com.b", "com.c"),
        disableBackgroundStops = disableBackgroundStops,
    )

    private fun session(profileId: String, startEpochMillis: Long = 1_000L) = BlockedProfileSession(
        id = "session-$profileId",
        profileId = profileId,
        tag = "manual",
        startTime = Instant.ofEpochMilli(startEpochMillis),
    )

    @Test
    fun `no pinned profile id resolves to NotConfigured`() {
        val state = resolveWidgetState(
            pinnedId = null,
            activeSession = null,
            activeProfile = null,
            profiles = listOf(profile("a")),
        )

        assertThat(state).isEqualTo(WidgetUiState.NotConfigured)
    }

    @Test
    fun `pinned profile id no longer present in profiles resolves to NotConfigured`() {
        val state = resolveWidgetState(
            pinnedId = "deleted-profile",
            activeSession = null,
            activeProfile = null,
            profiles = listOf(profile("a")),
        )

        assertThat(state).isEqualTo(WidgetUiState.NotConfigured)
    }

    @Test
    fun `pinned profile with no active session resolves to Idle with blocked count`() {
        val pinned = profile("a")

        val state = resolveWidgetState(
            pinnedId = "a",
            activeSession = null,
            activeProfile = null,
            profiles = listOf(pinned),
        )

        assertThat(state).isEqualTo(WidgetUiState.Idle(profileId = "a", profileName = "Profile a", blockedCount = 3))
    }

    @Test
    fun `pinned profile is the active session resolves to Active`() {
        val pinned = profile("a")
        val activeSession = session(profileId = "a", startEpochMillis = 5_000L)

        val state = resolveWidgetState(
            pinnedId = "a",
            activeSession = activeSession,
            activeProfile = pinned,
            profiles = listOf(pinned),
        )

        assertThat(state).isEqualTo(
            WidgetUiState.Active(
                profileId = "a",
                profileName = "Profile a",
                blockedCount = 3,
                sessionStartEpochMillis = 5_000L,
                disableBackgroundStops = false,
            ),
        )
    }

    @Test
    fun `pinned profile active with disableBackgroundStops propagates the flag`() {
        val pinned = profile("a", disableBackgroundStops = true)
        val activeSession = session(profileId = "a")

        val state = resolveWidgetState(
            pinnedId = "a",
            activeSession = activeSession,
            activeProfile = pinned,
            profiles = listOf(pinned),
        ) as WidgetUiState.Active

        assertThat(state.disableBackgroundStops).isTrue()
    }

    @Test
    fun `different profile active than pinned resolves to Unavailable`() {
        val pinned = profile("a")
        val other = profile("b", name = "Other Profile")
        val activeSession = session(profileId = "b")

        val state = resolveWidgetState(
            pinnedId = "a",
            activeSession = activeSession,
            activeProfile = other,
            profiles = listOf(pinned, other),
        )

        assertThat(state).isEqualTo(WidgetUiState.Unavailable(activeProfileName = "Other Profile"))
    }

    @Test
    fun `active session present but activeProfile null falls back to Idle`() {
        // Defensive: SessionManager keeps activeSession/activeProfile in lockstep, but a null
        // activeProfile (e.g. the profile row was deleted out from under an active session)
        // must not crash - it should fall through to the pinned profile's idle state.
        val pinned = profile("a")
        val activeSession = session(profileId = "a")

        val state = resolveWidgetState(
            pinnedId = "a",
            activeSession = activeSession,
            activeProfile = null,
            profiles = listOf(pinned),
        )

        assertThat(state).isEqualTo(WidgetUiState.Idle(profileId = "a", profileName = "Profile a", blockedCount = 3))
    }
}
