package com.foqos.android.di

import com.foqos.android.domain.session.RealSessionServiceController
import com.foqos.android.domain.session.SessionServiceController
import com.foqos.android.domain.session.WidgetRefresher
import com.foqos.android.widget.RealWidgetRefresher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds [SessionServiceController] to its real (Phase 5) [SessionForegroundService]-backed
 * implementation, and [WidgetRefresher] to the real Phase 8 [RealWidgetRefresher] (Glance
 * `updateAll`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {

    @Binds
    @Singleton
    abstract fun bindSessionServiceController(impl: RealSessionServiceController): SessionServiceController

    @Binds
    @Singleton
    abstract fun bindWidgetRefresher(impl: RealWidgetRefresher): WidgetRefresher
}
