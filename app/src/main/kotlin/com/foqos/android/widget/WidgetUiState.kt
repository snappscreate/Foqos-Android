package com.foqos.android.widget

import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.data.model.BlockedProfileSession

/**
 * Everything [ProfileWidget] needs to render, resolved from persisted/live session state. Kept
 * as a pure sealed type (no Android/Glance types inside) so [resolveWidgetState] is testable
 * without any widget host - mirrors iOS `ProfileWidgetEntry`'s derived `isSessionActive` /
 * `isBreakActive` booleans, collapsed into an explicit state machine instead.
 */
sealed interface WidgetUiState {

    /** No profile has ever been pinned to the widget (or the pinned profile was deleted). */
    data object NotConfigured : WidgetUiState

    /** The pinned profile has no active session - tapping the play button starts one. */
    data class Idle(
        val profileId: String,
        val profileName: String,
        val blockedCount: Int,
    ) : WidgetUiState

    /**
     * The pinned profile *is* the currently active session. [sessionStartEpochMillis] is a
     * snapshot, not a ticking clock - see [ProfileWidget] for why Glance can't animate this.
     */
    data class Active(
        val profileId: String,
        val profileName: String,
        val blockedCount: Int,
        val sessionStartEpochMillis: Long,
        val disableBackgroundStops: Boolean,
    ) : WidgetUiState

    /** A *different* profile than the one pinned to this widget is currently active. */
    data class Unavailable(val activeProfileName: String) : WidgetUiState
}

/**
 * Resolves what the widget should show, mirroring iOS `ProfileWidgetEntryView`'s
 * `isUnavailable`/`isSessionActive` branching:
 *  - No [pinnedId], or it no longer matches a profile in [profiles] -> [WidgetUiState.NotConfigured].
 *  - An [activeSession]/[activeProfile] exists and its profile id equals [pinnedId] -> [WidgetUiState.Active].
 *  - An [activeSession]/[activeProfile] exists for a *different* profile -> [WidgetUiState.Unavailable].
 *  - Otherwise the pinned profile is configured but idle -> [WidgetUiState.Idle].
 */
fun resolveWidgetState(
    pinnedId: String?,
    activeSession: BlockedProfileSession?,
    activeProfile: BlockedProfile?,
    profiles: List<BlockedProfile>,
): WidgetUiState {
    val pinnedProfile = pinnedId?.let { id -> profiles.firstOrNull { it.id == id } }
        ?: return WidgetUiState.NotConfigured

    if (activeSession != null && activeProfile != null) {
        return if (activeProfile.id == pinnedProfile.id) {
            WidgetUiState.Active(
                profileId = pinnedProfile.id,
                profileName = pinnedProfile.name,
                blockedCount = pinnedProfile.blockedPackages.size,
                sessionStartEpochMillis = activeSession.startTime.toEpochMilli(),
                disableBackgroundStops = pinnedProfile.disableBackgroundStops,
            )
        } else {
            WidgetUiState.Unavailable(activeProfileName = activeProfile.name)
        }
    }

    return WidgetUiState.Idle(
        profileId = pinnedProfile.id,
        profileName = pinnedProfile.name,
        blockedCount = pinnedProfile.blockedPackages.size,
    )
}
