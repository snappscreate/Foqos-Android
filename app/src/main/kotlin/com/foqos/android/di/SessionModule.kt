package com.foqos.android.di

import com.foqos.android.domain.session.NoOpWidgetRefresher
import com.foqos.android.domain.session.RealSessionServiceController
import com.foqos.android.domain.session.SessionServiceController
import com.foqos.android.domain.session.WidgetRefresher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [SessionServiceController] to its real (Phase 5) [SessionForegroundService]-backed
 * implementation, and [WidgetRefresher] to a placeholder until Phase 8 (widget) exists. Swap the
 * `WidgetRefresher` `@Binds` target to a real implementation once that phase lands - no other call
 * site needs to change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {

    @Binds
    @Singleton
    abstract fun bindSessionServiceController(impl: RealSessionServiceController): SessionServiceController

    @Binds
    @Singleton
    abstract fun bindWidgetRefresher(impl: NoOpWidgetRefresher): WidgetRefresher
}
