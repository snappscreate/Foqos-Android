package com.foqos.android.widget

import com.foqos.android.data.datastore.SettingsDataStore
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.domain.session.SessionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * [ProfileWidget]/[ProfileWidgetReceiver] are instantiated by Glance/the OS, not Hilt (Glance's
 * `GlanceAppWidgetReceiver` extends `BroadcastReceiver` one level removed, so `@AndroidEntryPoint`
 * can't attach to it - same reason [com.foqos.android.service.accessibility.ForegroundAppAccessibilityService]
 * uses this pattern). This [dagger.hilt.EntryPoint] lets both pull what they need straight out of
 * the singleton component.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun profileRepository(): ProfileRepository
    fun settingsDataStore(): SettingsDataStore
    fun sessionManager(): SessionManager
}

internal fun widgetEntryPoint(context: android.content.Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
