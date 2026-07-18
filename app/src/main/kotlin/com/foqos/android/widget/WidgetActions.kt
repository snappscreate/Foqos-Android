package com.foqos.android.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import com.foqos.android.MainActivity
import kotlinx.coroutines.flow.first

/**
 * Play/stop tap target for both [WidgetUiState.Idle] and [WidgetUiState.Active] (mirrors
 * [com.foqos.android.domain.session.SessionManager.toggleFromDeeplink]'s own toggle semantics -
 * idle starts the pinned profile, active stops it). Reads the pinned profile id fresh each tap
 * rather than threading it through [ActionParameters] so it can never act on a stale id.
 *
 * `ActionCallback`s are instantiated by Glance via reflection, not Hilt - hence [widgetEntryPoint]
 * rather than `@Inject`.
 */
class ToggleProfileAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val entryPoint = widgetEntryPoint(context)
        val pinnedId = entryPoint.settingsDataStore().pinnedWidgetProfileId.first() ?: return
        entryPoint.sessionManager().toggleFromDeeplink(pinnedId, fromBackground = true)
        ProfileWidget().updateAll(context)
    }
}

/**
 * Opens [MainActivity] without toggling anything - used for the "different profile active"
 * unavailable state and for the stop button when the pinned profile has `disableBackgroundStops`
 * set (mirrors [com.foqos.android.domain.session.SessionManager.stopActiveSession]'s
 * `fromBackground` guard: the widget must not be able to bypass it, so it hands off to the app
 * instead of stopping directly).
 */
class OpenAppAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
    }
}
