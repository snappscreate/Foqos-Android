package com.foqos.android.domain.session

import android.content.Context
import android.content.Intent
import com.foqos.android.service.session.SessionForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts/stops the foreground service that keeps a session's blocking + notification alive.
 * Implemented for real by [RealSessionServiceController] (`SessionForegroundService`);
 * [SessionManager] only depends on this interface so it can be unit-tested without any Android
 * service plumbing.
 */
interface SessionServiceController {
    fun startForegroundService(sessionId: String)
    fun stopForegroundService(sessionId: String)
}

/** No-op binding, kept around for tests that construct [SessionManager] without Android services. */
@Singleton
class NoOpSessionServiceController @Inject constructor() : SessionServiceController {
    override fun startForegroundService(sessionId: String) = Unit
    override fun stopForegroundService(sessionId: String) = Unit
}

/**
 * Real [SessionServiceController]: starts/stops [SessionForegroundService]. [sessionId] isn't
 * needed by the service itself (it always reflects whatever [com.foqos.android.service.session.BlockingStateHolder]
 * currently holds), it's only threaded through for parity/logging with the interface's contract.
 */
@Singleton
class RealSessionServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionServiceController {

    override fun startForegroundService(sessionId: String) {
        context.startForegroundService(Intent(context, SessionForegroundService::class.java))
    }

    // Lint's ImplicitSamInstance check flags this as if it were an identity-sensitive
    // bindService/ServiceConnection call, but Context.stopService(Intent) resolves its target by
    // component, not by Intent object identity - a fresh explicit Intent here is the standard,
    // correct way to stop a service (same pattern startForegroundService above uses to start it).
    @Suppress("ImplicitSamInstance")
    override fun stopForegroundService(sessionId: String) {
        context.stopService(Intent(context, SessionForegroundService::class.java))
    }
}
