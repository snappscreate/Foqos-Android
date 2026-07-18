package com.foqos.android.domain.session

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Triggers a reload of the home-screen widget's timeline after session state changes. Implemented
 * for real in Phase 8 (widget module); [SessionManager] only depends on this interface so it can
 * be unit-tested without any widget/glance plumbing.
 */
interface WidgetRefresher {
    fun refresh()
}

/** No-op default binding until Phase 8 wires up the real widget. */
@Singleton
class NoOpWidgetRefresher @Inject constructor() : WidgetRefresher {
    override fun refresh() = Unit
}
