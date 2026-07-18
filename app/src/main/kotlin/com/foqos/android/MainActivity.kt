package com.foqos.android

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.foqos.android.data.datastore.SettingsDataStore
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.service.nfc.NfcForegroundDispatchManager
import com.foqos.android.service.nfc.ProfileLinkCodec
import com.foqos.android.ui.navigation.FoqosNavHost
import com.foqos.android.ui.strategyflow.StrategyFlowHost
import com.foqos.android.ui.theme.FoqosTheme
import com.foqos.android.ui.theme.PRESET_SEED_COLORS
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * App entry point. Beyond the placeholder home content, this wires Phase 6's deep-link/NFC
 * delivery plumbing:
 *  - [NfcForegroundDispatchManager] foreground dispatch is (de)registered across
 *    `onResume`/`onPause` so scanned tags are delivered to `onNewIntent` while this activity is on
 *    screen.
 *  - A cold-launch NFC intent (`ACTION_NDEF_DISCOVERED`) or a plain deep link
 *    (`ACTION_VIEW` on `foqos://profile/{id}` / `https://foqos.app/profile/{id}`) both resolve to
 *    the same [SessionManager.toggleFromDeeplink] call.
 *  - [StrategyFlowHost] is mounted at the composition root so any in-app strategy start/stop that
 *    needs a scan/duration-pick/config sheet gets one, regardless of which screen is showing.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var sessionManager: SessionManager

    @Inject lateinit var profileRepository: ProfileRepository

    @Inject lateinit var nfcDispatchManager: NfcForegroundDispatchManager

    @Inject lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            val themeColorIndex by settingsDataStore.themeColorIndex.collectAsStateWithLifecycle(initialValue = SettingsDataStore.DEFAULT_THEME_COLOR_INDEX)
            val darkModeOverride by settingsDataStore.darkModeOverride.collectAsStateWithLifecycle(initialValue = SettingsDataStore.DEFAULT_DARK_MODE_OVERRIDE)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (darkModeOverride) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            val seedColor = PRESET_SEED_COLORS.getOrNull(themeColorIndex)

            FoqosTheme(darkTheme = darkTheme, dynamicColor = seedColor == null, seedColor = seedColor) {
                Box(modifier = Modifier.fillMaxSize()) {
                    FoqosNavHost(sessionManager = sessionManager, settingsDataStore = settingsDataStore)
                    StrategyFlowHost(
                        sessionManager = sessionManager,
                        profileRepository = profileRepository,
                        nfcDispatchManager = nfcDispatchManager,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcDispatchManager.enable(this)
    }

    override fun onPause() {
        nfcDispatchManager.disable(this)
        super.onPause()
    }

    /**
     * Resolves a profile id out of [intent] - either an NFC tag scan (foreground dispatch or a
     * cold `ACTION_NDEF_DISCOVERED` launch) or a plain `ACTION_VIEW` deep link - and toggles that
     * profile's session. `fromBackground = false` throughout: by the time this runs the app is
     * open/foregrounded, so `disableBackgroundStops` must not block the toggle.
     */
    private fun handleIncomingIntent(intent: Intent) {
        when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_TAG_DISCOVERED,
            -> nfcDispatchManager.handleIntent(intent) { profileId -> toggleProfile(profileId) }

            Intent.ACTION_VIEW -> {
                val profileId = intent.dataString?.let(ProfileLinkCodec::parseProfileId)
                if (profileId != null) toggleProfile(profileId)
            }
        }
    }

    private fun toggleProfile(profileId: String) {
        lifecycleScope.launch {
            sessionManager.toggleFromDeeplink(profileId, fromBackground = false)
        }
    }
}
