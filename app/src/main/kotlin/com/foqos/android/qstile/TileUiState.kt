package com.foqos.android.qstile

import com.foqos.android.data.model.BlockedProfile

/** What [FoqosTileService] should show, resolved from live session state + the pinned profile. */
sealed interface TileUiState {

    /** A session is currently active - tapping the tile stops it (or opens the app, see [canStopFromTile]). */
    data class Active(val profileName: String, val canStopFromTile: Boolean) : TileUiState

    /** Nothing is active. Tapping the tile starts [profileId] if set, otherwise just opens the app. */
    data class Inactive(val profileId: String?, val label: String) : TileUiState
}

/**
 * Resolves the quick-settings tile's state:
 *  - [activeProfile] present -> [TileUiState.Active], label = its name, `canStopFromTile` = `!disableBackgroundStops`
 *    (mirrors [com.foqos.android.domain.session.SessionManager.stopActiveSession]'s background-stop guard).
 *  - Otherwise -> [TileUiState.Inactive], label = [pinnedProfile]'s name if one is pinned, else "Foqos".
 */
fun resolveTileState(activeProfile: BlockedProfile?, pinnedProfile: BlockedProfile?): TileUiState =
    if (activeProfile != null) {
        TileUiState.Active(profileName = activeProfile.name, canStopFromTile = !activeProfile.disableBackgroundStops)
    } else {
        TileUiState.Inactive(profileId = pinnedProfile?.id, label = pinnedProfile?.name ?: "Foqos")
    }
