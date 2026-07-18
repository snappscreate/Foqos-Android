package com.foqos.android.domain.session

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts/stops the foreground service that keeps a session's blocking + notification alive.
 * Implemented for real in Phase 5 (`SessionForegroundService`); [SessionManager] only depends on
 * this interface so it can be unit-tested without any Android service plumbing.
 */
interface SessionServiceController {
    fun startForegroundService(sessionId: String)
    fun stopForegroundService(sessionId: String)
}

/** No-op default binding until Phase 5 wires up the real foreground service. */
@Singleton
class NoOpSessionServiceController @Inject constructor() : SessionServiceController {
    override fun startForegroundService(sessionId: String) = Unit
    override fun stopForegroundService(sessionId: String) = Unit
}
