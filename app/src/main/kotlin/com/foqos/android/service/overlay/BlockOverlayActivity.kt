package com.foqos.android.service.overlay

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager as AndroidPackageManager
import android.location.Location
import android.nfc.NfcAdapter
import android.widget.ImageView
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.foqos.android.BuildConfig
import com.foqos.android.data.model.PhysicalUnblockType
import com.foqos.android.domain.strategy.StrategyInput
import com.foqos.android.domain.strategy.StrategyRegistry
import com.foqos.android.domain.strategy.SessionOutcome
import com.foqos.android.service.location.LocationAutomationStore
import com.foqos.android.service.nfc.NfcForegroundDispatchManager
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.collect
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foqos.android.data.model.SoftUnblockStrategyData
import com.foqos.android.data.repository.ProfileRepository
import com.foqos.android.data.repository.SoftUnblockRepository
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.domain.strategy.NfcSoftUnblockBlockingStrategy
import com.foqos.android.domain.strategy.QrSoftUnblockBlockingStrategy
import com.foqos.android.service.session.BlockingStateHolder
import com.foqos.android.ui.block.BlockScreenContent
import com.foqos.android.ui.block.SoftUnblockInfo
import com.foqos.android.ui.theme.FoqosTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Shows [BlockScreenContent] full-screen over whatever app/website
 * [com.foqos.android.service.accessibility.ForegroundAppAccessibilityService] (or the
 * [com.foqos.android.service.session.UsageStatsPoller] fallback) just decided to block. Declared
 * `excludeFromRecents` + `singleTask` in the manifest so relaunching it for the same or a
 * different blocked package reuses/updates the same instance rather than stacking overlays.
 *
 * Auto-finishes (and sends the user home) if the blocking session ends, or if a soft-unblock grant
 * gets issued for the blocked package while this is showing - both observed live via
 * [BlockingStateHolder].
 */
@AndroidEntryPoint
class BlockOverlayActivity : ComponentActivity() {

    @Inject lateinit var blockingStateHolder: BlockingStateHolder

    @Inject lateinit var profileRepository: ProfileRepository

    @Inject lateinit var softUnblockRepository: SoftUnblockRepository

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var nfcDispatchManager: NfcForegroundDispatchManager

    private var nfcScanMessage by mutableStateOf<String?>(null)
    private var boundaryDistance by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (targetPackage.isNullOrBlank()) {
            finish()
            return
        }
        val targetDomain = intent.getStringExtra(EXTRA_DOMAIN)

        lifecycleScope.launch {
            nfcDispatchManager.tagScans.collect { content ->
                try {
                    processNfcCode(content.value)
                } catch (error: Exception) {
                    nfcScanMessage = "NFC scan failed: ${error.message}"
                }
            }
        }
        lifecycleScope.launch {
            sessionManager.errors.collect { error -> nfcScanMessage = error }
        }
        refreshBoundaryDistance()

        setContent {
            FoqosTheme {
                val state by blockingStateHolder.state.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()

                // Auto-finishes on session end or once a grant for this exact package is issued
                // (by this screen's own "Open for N min" button below, or another path entirely).
                LaunchedEffect(state, targetPackage) {
                    val current = state
                    if (current == null) {
                        finishAndGoHome()
                        return@LaunchedEffect
                    }
                    // A pause or break must also close an already displayed domain/app shield.
                    if (current.pauseActive || current.breakActive) {
                        finish()
                        return@LaunchedEffect
                    }
                    val grantExpiry = current.activeGrants[targetPackage]
                    if (grantExpiry != null && grantExpiry > System.currentTimeMillis()) {
                        finish()
                    }
                }

                var softUnblockInfo by remember { mutableStateOf<SoftUnblockInfo?>(null) }
                LaunchedEffect(state?.profileId, state?.sessionId) {
                    val current = state
                    softUnblockInfo = if (current == null) {
                        null
                    } else {
                        loadSoftUnblockInfo(current.profileId, current.sessionId)
                    }
                }

                val blockedLabel = targetDomain ?: rememberAppLabel(targetPackage)
                val activeProfile by sessionManager.activeProfile.collectAsStateWithLifecycle()
                val nfcReady = activeProfile?.let { profile ->
                    StrategyRegistry.byId(profile.strategyId).usesNfc
                } ?: false

                BlockScreenContent(
                    profileName = state?.profileName.orEmpty(),
                    blockedLabel = blockedLabel,
                    sessionStartEpochMillis = state?.sessionStartEpochMillis ?: System.currentTimeMillis(),
                    softUnblockInfo = softUnblockInfo,
                    blockedIcon = {
                        if (targetDomain == null) {
                            AndroidView(
                                factory = { context ->
                                    ImageView(context).apply {
                                        setImageDrawable(runCatching {
                                            context.packageManager.getApplicationIcon(targetPackage)
                                        }.getOrNull())
                                    }
                                },
                                modifier = Modifier.size(64.dp),
                            )
                        } else {
                            Text("🌐", fontSize = 40.sp)
                        }
                    },
                    nfcReady = nfcReady,
                    nfcMessage = nfcScanMessage,
                    distanceDescription = boundaryDistance,
                    onSimulateNfc = if (BuildConfig.DEBUG && nfcReady) ({
                        lifecycleScope.launch {
                            val profile = activeProfile
                            val session = sessionManager.activeSession.value
                            if (profile != null && session != null) {
                                val code = profile.physicalUnblockItems.firstOrNull {
                                    it.type == PhysicalUnblockType.NFC
                                }?.codeValue ?: "foqos-debug-virtual-tag"
                                processNfcCode(code)
                            }
                        }
                    }) else null,
                    onGoBack = { finishAndGoHome() },
                    onOpenFoqosHome = { openFoqosHome() },
                    onOpenTemporarily = {
                        scope.launch {
                            sessionManager.issueSoftUnblockGrant(targetPackage)
                            finish()
                        }
                    },
                )

                BackHandler { finishAndGoHome() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcDispatchManager.setWaitingForScan(true)
        runCatching { nfcDispatchManager.enable(this) }
        refreshBoundaryDistance()
    }

    override fun onPause() {
        runCatching { nfcDispatchManager.disable(this) }
        nfcDispatchManager.setWaitingForScan(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        nfcDispatchManager.handleIntent(intent) { }
    }

    private suspend fun processNfcCode(code: String) {
        val session = sessionManager.activeSession.value ?: return
        val profile = profileRepository.getById(session.profileId) ?: return
        if (!StrategyRegistry.byId(profile.strategyId).usesNfc) return
        // Never silently accept unknown NFC codes, even on location-started sessions.
        val registered = profile.physicalUnblockItems.filter { it.type == PhysicalUnblockType.NFC }
        if (registered.isNotEmpty() && !profile.canUnblock(code, PhysicalUnblockType.NFC)) {
            nfcScanMessage = "このNFCは登録されていません"
            return
        }
        // Existing strategy performs the final NFC permission and pause-only checks.
        when (val outcome = StrategyRegistry.byId(profile.strategyId).onStopInput(
            profile, session, StrategyInput.Code(code, StrategyInput.CodeSource.NFC), sessionManager
        )) {
            SessionOutcome.Paused -> nfcScanMessage = "一時停止を開始したよ"
            SessionOutcome.Stopped -> nfcScanMessage = "ブロックを解除したよ"
            is SessionOutcome.Rejected -> nfcScanMessage =
                if (registered.isNotEmpty()) "このNFCは登録されていません、または現在は使用できません"
                else outcome.reason
            else -> nfcScanMessage = "NFCを読み取ったよ"
        }
    }

    private fun refreshBoundaryDistance() {
        val rule = LocationAutomationStore(this).read() ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            AndroidPackageManager.PERMISSION_GRANTED
        ) return
        @Suppress("MissingPermission")
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null && sessionManager.activeSession.value?.profileId == rule.profileId) {
                    val results = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude, location.longitude,
                        rule.latitude, rule.longitude, results,
                    )
                    val toBoundary = (results[0] - rule.radiusMeters).coerceAtLeast(0f)
                    boundaryDistance = if (toBoundary == 0f) "Inside blocking area" else
                        "Distance to blocking area: ${toBoundary.toInt()} m"
                }
            }
    }

    private suspend fun loadSoftUnblockInfo(profileId: String, sessionId: String): SoftUnblockInfo? {
        val profile = profileRepository.getById(profileId) ?: return null
        if (profile.strategyId !in SOFT_UNBLOCK_STRATEGY_IDS) return null

        val sessionState = softUnblockRepository.getSessionState(sessionId) ?: return null
        if (sessionState.remainingUnblockCount <= 0) return null

        val accessMinutes = SoftUnblockStrategyData.fromJsonOrNull(profile.strategyDataJson).accessDurationInMinutes
        return SoftUnblockInfo(remainingOpens = sessionState.remainingUnblockCount, accessMinutes = accessMinutes)
    }

    @androidx.compose.runtime.Composable
    private fun rememberAppLabel(packageName: String): String {
        val context = androidx.compose.ui.platform.LocalContext.current
        return remember(packageName) {
            runCatching {
                val pm = context.packageManager
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(packageName, 0)
                }
                pm.getApplicationLabel(appInfo).toString()
            }.getOrDefault(packageName)
        }
    }

    private fun openFoqosHome() {
        // Navigate into the actual Foqos UI rather than another full-screen active-session UI.
        finish()
        startActivity(Intent(this, com.foqos.android.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }

    private fun finishAndGoHome() {
        finishAndRemoveTask()
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
        )
    }

    companion object {
        const val EXTRA_PACKAGE_NAME = "com.foqos.android.extra.BLOCKED_PACKAGE_NAME"
        const val EXTRA_DOMAIN = "com.foqos.android.extra.BLOCKED_DOMAIN"

        private val SOFT_UNBLOCK_STRATEGY_IDS = setOf(
            NfcSoftUnblockBlockingStrategy.ID,
            QrSoftUnblockBlockingStrategy.ID,
        )
    }
}
