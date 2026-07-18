package com.foqos.android.di

import com.foqos.android.domain.session.NoOpSessionServiceController
import com.foqos.android.domain.session.NoOpWidgetRefresher
import com.foqos.android.domain.session.SessionServiceController
import com.foqos.android.domain.session.WidgetRefresher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the placeholder [SessionServiceController]/[WidgetRefresher] implementations so
 * [com.foqos.android.domain.session.SessionManager] has something to depend on before Phase 5
 * (foreground service) and Phase 8 (widget) exist. Swap these `@Binds` targets to the real
 * implementations once those phases land - no other call site needs to change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {

    @Binds
    @Singleton
    abstract fun bindSessionServiceController(impl: NoOpSessionServiceController): SessionServiceController

    @Binds
    @Singleton
    abstract fun bindWidgetRefresher(impl: NoOpWidgetRefresher): WidgetRefresher
}
