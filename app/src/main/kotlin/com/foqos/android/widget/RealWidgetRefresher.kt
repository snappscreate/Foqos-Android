package com.foqos.android.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.foqos.android.di.IoDispatcher
import com.foqos.android.domain.session.WidgetRefresher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Real [WidgetRefresher]: reloads [ProfileWidget]'s Glance timeline on its own scope so
 * [com.foqos.android.domain.session.SessionManager] (a plain, non-Android-aware class) never has
 * to await widget work as part of starting/ending a session.
 */
@Singleton
class RealWidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : WidgetRefresher {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    override fun refresh() {
        scope.launch {
            ProfileWidget().updateAll(context)
        }
    }
}
