package com.foqos.android.qstile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.foqos.android.MainActivity
import com.foqos.android.data.datastore.SettingsDataStore
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.domain.session.SessionManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * [TileService] is instantiated by the system, and its direct superclass is `TileService` (not
 * `Service` itself), so `@AndroidEntryPoint` cannot attach here for the same reason
 * [com.foqos.android.service.accessibility.ForegroundAppAccessibilityService] uses an
 * [dagger.hilt.EntryPoint] instead of Hilt's Service support.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TileServiceEntryPoint {
    fun sessionManager(): SessionManager
    fun profileRepository(): ProfileRepository
    fun settingsDataStore(): SettingsDataStore
}

/**
 * Quick-settings tile mirroring the widget's toggle behavior: active -> stop (or open the app if
 * `disableBackgroundStops`), inactive -> start the pinned profile (or just open the app if nothing
 * is pinned).
 */
class FoqosTileService : TileService() {

    private lateinit var sessionManager: SessionManager
    private lateinit var profileRepository: ProfileRepository
    private lateinit var settingsDataStore: SettingsDataStore

    private var listeningJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, TileServiceEntryPoint::class.java)
        sessionManager = entryPoint.sessionManager()
        profileRepository = entryPoint.profileRepository()
        settingsDataStore = entryPoint.settingsDataStore()
    }

    override fun onStartListening() {
        super.onStartListening()
        listeningJob = scope.launch {
            combine(
                sessionManager.activeProfile,
                settingsDataStore.pinnedWidgetProfileId,
            ) { activeProfile, pinnedId -> activeProfile to pinnedId }
                .collectLatest { (activeProfile, pinnedId) ->
                    val pinnedProfile = pinnedId?.let { profileRepository.getById(it) }
                    applyState(resolveTileState(activeProfile, pinnedProfile))
                }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val activeSession = sessionManager.activeSession.value
            val activeProfile = sessionManager.activeProfile.value
            if (activeSession != null && activeProfile != null) {
                if (activeProfile.disableBackgroundStops) {
                    openApp()
                } else {
                    sessionManager.toggleFromDeeplink(activeProfile.id, fromBackground = true)
                }
                return@launch
            }

            val pinnedId = settingsDataStore.pinnedWidgetProfileId.first()
            if (pinnedId != null) {
                sessionManager.toggleFromDeeplink(pinnedId, fromBackground = true)
            } else {
                openApp()
            }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            // Only reachable below API 34, where the PendingIntent overload doesn't exist yet -
            // lint's StartActivityAndCollapseDeprecated flags this because the Intent overload
            // throws UnsupportedOperationException on API 34+, which the branch above already
            // avoids entirely.
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun applyState(state: TileUiState) {
        val tile = qsTile ?: return
        when (state) {
            is TileUiState.Active -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = state.profileName
            }
            is TileUiState.Inactive -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = state.label
            }
        }
        tile.updateTile()
    }
}
