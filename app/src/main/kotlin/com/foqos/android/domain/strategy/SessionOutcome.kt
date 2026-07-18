package com.foqos.android.domain.strategy

import com.foqos.android.data.model.BlockedProfileSession

/** The result of a [BlockingStrategy]'s `onStartInput` / `onStopInput`. */
sealed interface SessionOutcome {
    data class Started(val session: BlockedProfileSession) : SessionOutcome
    data object Stopped : SessionOutcome
    data object Paused : SessionOutcome
    data class Rejected(val reason: String) : SessionOutcome
}
