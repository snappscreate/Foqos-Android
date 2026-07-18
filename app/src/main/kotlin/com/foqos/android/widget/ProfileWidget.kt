package com.foqos.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first

/**
 * Home-screen widget mirroring iOS `ProfileControlWidget`'s home-screen (`.systemSmall`) layout:
 * pinned-profile name, blocked-app count, and either a start button (idle) or a stop button
 * (active) - plus the "different profile active" unavailable overlay.
 *
 * **Elapsed-time staleness**: Glance has no chronometer/ticking-clock primitive (unlike
 * `NotificationCompat.setUsesChronometer` used for the session notification) - the elapsed time
 * shown here is a plain [Text] computed from [WidgetUiState.Active.sessionStartEpochMillis] at the
 * moment [provideGlance] runs, and is only recomputed when [RealWidgetRefresher.refresh] triggers
 * `updateAll` (session start/stop) or the OS periodically wakes the widget for its own update
 * cycle. Between those points the displayed elapsed time is stale by design - this is a documented
 * v1 limitation, not a bug.
 */
class ProfileWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SMALL_SIZE, MEDIUM_SIZE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = widgetEntryPoint(context)
        val pinnedId = entryPoint.settingsDataStore().pinnedWidgetProfileId.first()
        val activeSession = entryPoint.sessionManager().activeSession.value
        val activeProfile = entryPoint.sessionManager().activeProfile.value
        val profiles = entryPoint.profileRepository().observeAll().first()
        val state = resolveWidgetState(pinnedId, activeSession, activeProfile, profiles)
        val nowEpochMillis = Instant.now().toEpochMilli()

        provideContent {
            GlanceTheme {
                WidgetContent(state = state, nowEpochMillis = nowEpochMillis)
            }
        }
    }

    companion object {
        /** ~2x1 home-screen cell. */
        val SMALL_SIZE = DpSize(100.dp, 50.dp)

        /** ~2x2 home-screen cell. */
        val MEDIUM_SIZE = DpSize(180.dp, 110.dp)
    }
}

@Composable
private fun WidgetContent(state: WidgetUiState, nowEpochMillis: Long) {
    val isCompact = LocalSize.current.height < MEDIUM_HEIGHT_THRESHOLD

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp)
            .rootClickModifier(state),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        when (state) {
            WidgetUiState.NotConfigured -> NotConfiguredContent()
            is WidgetUiState.Idle -> IdleContent(state, isCompact)
            is WidgetUiState.Active -> ActiveContent(state, isCompact, nowEpochMillis)
            is WidgetUiState.Unavailable -> UnavailableContent(state)
        }
    }
}

/** Only the whole-surface states (no-config / unavailable) are clickable at the root. */
private fun GlanceModifier.rootClickModifier(state: WidgetUiState): GlanceModifier = when (state) {
    WidgetUiState.NotConfigured -> clickable(actionStartActivity<ProfileWidgetConfigActivity>())
    is WidgetUiState.Unavailable -> clickable(actionRunCallback<OpenAppAction>())
    else -> this
}

@Composable
private fun NotConfiguredContent() {
    Text(
        text = "Tap to set up",
        style = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        ),
    )
}

@Composable
private fun IdleContent(state: WidgetUiState.Idle, isCompact: Boolean) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = state.profileName,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            if (!isCompact) {
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    text = "${state.blockedCount} apps blocked",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        RoundIconButton(
            glyph = "▶", // ▶ play
            contentDescription = "Start ${state.profileName}",
            action = actionRunCallback<ToggleProfileAction>(),
        )
    }
}

@Composable
private fun ActiveContent(state: WidgetUiState.Active, isCompact: Boolean, nowEpochMillis: Long) {
    val elapsed = formatElapsed(state.sessionStartEpochMillis, nowEpochMillis)
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = state.profileName,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = elapsed,
                style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
        }
        Spacer(modifier = GlanceModifier.width(8.dp))
        RoundIconButton(
            glyph = "■", // ■ stop
            contentDescription = if (state.disableBackgroundStops) "Open Foqos" else "Stop ${state.profileName}",
            action = if (state.disableBackgroundStops) {
                actionRunCallback<OpenAppAction>()
            } else {
                actionRunCallback<ToggleProfileAction>()
            },
        )
    }
}

@Composable
private fun UnavailableContent(state: WidgetUiState.Unavailable) {
    Column(horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
        Text(
            text = "In session:",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center),
            maxLines = 1,
        )
        Text(
            text = state.activeProfileName,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun RoundIconButton(glyph: String, contentDescription: String, action: androidx.glance.action.Action) {
    Column(
        modifier = GlanceModifier
            .width(36.dp)
            .height(36.dp)
            .background(GlanceTheme.colors.primary)
            .clickable(action),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = glyph,
            style = TextStyle(color = GlanceTheme.colors.onPrimary, fontWeight = FontWeight.Bold),
        )
    }
    // contentDescription is intentionally unused beyond documentation: Glance's Text/clickable
    // don't currently expose a semantics/content-description modifier in glance-appwidget 1.1.1.
}

/** Formats [startEpochMillis] -> [nowEpochMillis] as `H:MM:SS` (or `MM:SS` under an hour). */
internal fun formatElapsed(startEpochMillis: Long, nowEpochMillis: Long): String {
    val seconds = Duration.between(Instant.ofEpochMilli(startEpochMillis), Instant.ofEpochMilli(nowEpochMillis))
        .seconds
        .coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}

private val MEDIUM_HEIGHT_THRESHOLD = 80.dp
