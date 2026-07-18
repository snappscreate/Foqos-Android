package com.foqos.android.qstile

import com.foqos.android.data.model.BlockedProfile
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure-logic coverage for [resolveTileState] - no `TileService`/Android types involved. */
class TileUiStateTest {

    private fun profile(id: String, name: String = "Profile $id", disableBackgroundStops: Boolean = false) = BlockedProfile(
        id = id,
        name = name,
        disableBackgroundStops = disableBackgroundStops,
    )

    @Test
    fun `active profile resolves to Active with its name and stoppable true`() {
        val active = profile("a", name = "Deep Work")

        val state = resolveTileState(activeProfile = active, pinnedProfile = null)

        assertThat(state).isEqualTo(TileUiState.Active(profileName = "Deep Work", canStopFromTile = true))
    }

    @Test
    fun `active profile with disableBackgroundStops resolves to Active with stoppable false`() {
        val active = profile("a", name = "Deep Work", disableBackgroundStops = true)

        val state = resolveTileState(activeProfile = active, pinnedProfile = null)

        assertThat(state).isEqualTo(TileUiState.Active(profileName = "Deep Work", canStopFromTile = false))
    }

    @Test
    fun `no active profile with a pinned profile resolves to Inactive with the pinned name`() {
        val pinned = profile("p", name = "Focus Session")

        val state = resolveTileState(activeProfile = null, pinnedProfile = pinned)

        assertThat(state).isEqualTo(TileUiState.Inactive(profileId = "p", label = "Focus Session"))
    }

    @Test
    fun `no active profile and no pinned profile resolves to Inactive with the Foqos fallback label`() {
        val state = resolveTileState(activeProfile = null, pinnedProfile = null)

        assertThat(state).isEqualTo(TileUiState.Inactive(profileId = null, label = "Foqos"))
    }
}
