package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfileSession
import java.time.Instant

/**
 * Lifecycle events a [StrategyHost] implementation (the Phase 4 `SessionManager`) publishes as a
 * result of strategy actions - mirrors the iOS `SessionStatus` enum (`.started` / `.ended` /
 * `.paused`).
 */
sealed interface SessionEvent {
    data class Started(val session: BlockedProfileSession) : SessionEvent
    data class Ended(val profileId: String) : SessionEvent
    data class Paused(val profileId: String, val pauseEndsAt: Instant) : SessionEvent
}
