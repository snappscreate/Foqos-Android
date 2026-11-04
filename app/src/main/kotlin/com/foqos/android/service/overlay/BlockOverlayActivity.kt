package com.foqos.android.service.overlay

import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import com.foqos.android.service.session.FullScreenModeStore
import com.foqos.android.service.session.DefaultBrowserResolver
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager as AndroidPackageManager
import android.location.Location
import android.nfc.NfcAdapter
import android.nfc.tech.Ndef
import android.util.Log
import com.foqos.android.service.nfc.NfcTagContentParser
import android.widget.ImageView
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
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
import com.foqos.android.service.session.PeriodicAccessSchedule
import com.foqos.android.service.session.PeriodicAccessStore
import com.foqos.android.ui.common.DeveloperUiVisibility
import com.foqos.android.ui.block.BlockScreenContent
import com.foqos.android.ui.block.FullScreenShortcutGrid
import com.foqos.android.ui.block.SoftUnblockInfo
import com.foqos.android.ui.theme.FoqosTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
        val locationPauseRequest = intent.getBooleanExtra(EXTRA_LOCATION_PAUSE_REQUEST, false)

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
                val fullScreenStore = remember { FullScreenModeStore(this@BlockOverlayActivity) }
                val locationStore = remember { LocationAutomationStore(this@BlockOverlayActivity) }
                val locationStatus by remember { locationStore.observeState() }
                    .collectAsStateWithLifecycle(
                        initialValue = locationStore.read() to
                            (locationStore.manualInside() ?: locationStore.lastInside()),
                    )

                val fullScreen = state?.profileId?.let(fullScreenStore::enabled) == true
                val allowedPackages = state?.profileId?.let(fullScreenStore::allowedPackages).orEmpty()
                val allowedUrls = state?.profileId?.let(fullScreenStore::allowedUrls).orEmpty()
                var showAllowedApps by remember { mutableStateOf(false) }
                var periodicNow by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(state?.sessionId, state?.periodicWaitMinutes) {
                    while (true) {
                        delay(1_000L)
                        periodicNow = System.currentTimeMillis()
                    }
                }


                // Auto-finishes on session end or once a grant for this exact package is issued
                // (by this screen's own "Open for N min" button below, or another path entirely).
                LaunchedEffect(state, targetPackage, locationStatus, periodicNow) {
                    val current = state
                    if (current == null) {
                        finishAndGoHome()
                        return@LaunchedEffect
                    }
                    // Full-screen focus pause must return to the real Android launcher.
                    // Simply finish() can reveal an underlying blocked app instead.
                    val outside = locationStatus.first?.profileId == current.profileId &&
                        locationStatus.second == false
                    val periodicOpen = PeriodicAccessSchedule.isAccessible(
                        PeriodicAccessStore.Config(current.periodicWaitMinutes, current.periodicAccessMinutes),
                        current.periodicAnchorEpochMillis, periodicNow,
                    )
                    if (current.pauseActive || current.breakActive || periodicOpen || current.locationSuspended || outside) {
                        if (fullScreen) finishAndGoHome() else finish()
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

                val blockedLabel = when {
                    locationPauseRequest -> "一時停止の認証"
                    targetPackage == FULL_SCREEN_HOME_TARGET -> "集中モード"
                    else -> targetDomain ?: rememberAppLabel(targetPackage)
                }
                val activeProfile by sessionManager.activeProfile.collectAsStateWithLifecycle()
                val nfcReady = activeProfile?.let { profile ->
                    StrategyRegistry.byId(profile.strategyId).usesNfc ||
                        (locationPauseRequest && profile.physicalUnblockItems.any {
                            it.type == PhysicalUnblockType.NFC
                        })
                } ?: false

                BlockScreenContent(
                    profileName = state?.profileName.orEmpty(),
                    blockedLabel = blockedLabel,
                    sessionStartEpochMillis = state?.sessionStartEpochMillis ?: System.currentTimeMillis(),
                    softUnblockInfo = softUnblockInfo,
                    blockedIcon = {
                        if (targetDomain == null && targetPackage != FULL_SCREEN_HOME_TARGET) {
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
                    onSimulateNfc = if (BuildConfig.DEBUG && DeveloperUiVisibility.showDiagnosticControls && nfcReady) ({
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
                    fullScreenMode = fullScreen || locationPauseRequest,
                    fullScreenShortcuts = if (fullScreen) ({
                        FullScreenShortcutGrid(
                            packages = allowedPackages,
                            urls = allowedUrls,
                            appLabel = { pkg ->
                                runCatching {
                                    val info = packageManager.getApplicationInfo(pkg, 0)
                                    packageManager.getApplicationLabel(info).toString()
                                }.getOrDefault(pkg)
                            },
                            onPhone = {
                                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
                                runCatching {
                                    startActivity(dialIntent)
                                    finish()
                                }.onFailure {
                                    nfcScanMessage = "電話アプリを起動できませんでした"
                                }
                            },
                            onApp = { pkg ->
                                val launch = packageManager.getLaunchIntentForPackage(pkg)
                                if (launch != null) {
                                    startActivity(launch)
                                    finish()
                                } else nfcScanMessage = "アプリを起動できませんでした"
                            },
                            onUrl = { url ->
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addCategory(Intent.CATEGORY_BROWSABLE)
                                val resolvedPackage = DefaultBrowserResolver.packageName(this@BlockOverlayActivity)
                                val profileId = state?.profileId
                                if (profileId == null || resolvedPackage == null) {
                                    nfcScanMessage = "ブラウザを特定できません。Android設定で標準ブラウザを選択してください"
                                } else {
                                    browserIntent.setPackage(resolvedPackage)
                                    // Register the concrete browser package *before* launching.
                                    // This is what the accessibility policy checks while loading.
                                    fullScreenStore.grantBrowserStartup(profileId, resolvedPackage)
                                    runCatching {
                                        startActivity(browserIntent)
                                        finish()
                                    }.onFailure {
                                        nfcScanMessage = "ブラウザを起動できませんでした"
                                    }
                                }
                            },
                        )
                    }) else null,
                    onOpenAllowedApps = null,
                    onOpenAndroidSettings = if (fullScreen) ({
                        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }) else null,
                    onOpenTemporarily = {
                        scope.launch {
                            sessionManager.issueSoftUnblockGrant(targetPackage)
                            finish()
                        }
                    },
                )

                if (showAllowedApps) {
                    AlertDialog(
                        onDismissRequest = { showAllowedApps = false },
                        title = { Text("許可したアプリ") },
                        text = {
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.heightIn(max = 360.dp),
                            ) {
                                items(allowedPackages.toList().sorted()) { pkg ->
                                    val label = rememberAppLabel(pkg)
                                    TextButton(onClick = {
                                        showAllowedApps = false
                                        val launch = packageManager.getLaunchIntentForPackage(pkg)
                                        if (launch != null) {
                                            startActivity(launch)
                                            finish()
                                        } else {
                                            nfcScanMessage = "アプリを起動できませんでした：$pkg"
                                        }
                                    }) { Text(label) }
                                }
                                if (allowedPackages.isEmpty()) {
                                    item { Text("許可アプリが登録されていません") }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showAllowedApps = false }) { Text("閉じる") }
                        },
                    )
                }

                BackHandler { if (!fullScreen) finishAndGoHome() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reader mode delivers a Tag directly to this visible shield. Foreground
        // dispatch alone can be bypassed by NDEF intent routing on Pixel devices.
        val adapter = NfcAdapter.getDefaultAdapter(this)
        if (adapter == null || !adapter.isEnabled) {
            nfcScanMessage = "NFCが無効です。設定でNFCをオンにしてください"
        } else {
            val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V
            runCatching {
                adapter.enableReaderMode(this, { tag ->
                    // Reader callback is on a binder thread. Read NDEF there, then
                    // hand the resulting code to the same strategy as the debug button.
                    val content = runCatching {
                        val ndef = Ndef.get(tag)
                        val message = ndef?.cachedNdefMessage ?: ndef?.let {
                            try {
                                it.connect()
                                it.ndefMessage
                            } finally {
                                runCatching { it.close() }
                            }
                        }
                        NfcTagContentParser.fromTag(tag, message)
                    }
                    lifecycleScope.launch {
                        if (content.isSuccess) {
                            val parsed = content.getOrThrow()
                            Log.d("FoqosNfcShield", "Reader received NFC: source=${parsed.source}")
                            processNfcCode(parsed.value)
                        } else {
                            Log.w("FoqosNfcShield", "Reader could not parse NFC", content.exceptionOrNull())
                            nfcScanMessage = "NFCの読み取りに失敗しました"
                        }
                    }
                }, flags, null)
            }.onFailure {
                Log.e("FoqosNfcShield", "Unable to enable NFC reader mode", it)
                nfcScanMessage = "NFCリーダーを開始できませんでした"
            }
        }
        refreshBoundaryDistance()
    }

    override fun onPause() {
        runCatching { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) }
        super.onPause()
    }

    private suspend fun processNfcCode(code: String) {
        val session = sessionManager.activeSession.value ?: return
        val profile = profileRepository.getById(session.profileId) ?: return
        if (!StrategyRegistry.byId(profile.strategyId).usesNfc &&
            !(intent.getBooleanExtra(EXTRA_LOCATION_PAUSE_REQUEST, false) &&
                profile.physicalUnblockItems.any { it.type == PhysicalUnblockType.NFC })) return
        // Never silently accept unknown NFC codes, even on location-started sessions.
        val registered = profile.physicalUnblockItems.filter { it.type == PhysicalUnblockType.NFC }
        val requiresRegistration = session.tag == SessionManager.LOCATION_SESSION_TAG ||
            profile.strategyId == com.foqos.android.domain.strategy.NfcPauseTimerBlockingStrategy.ID
        if ((requiresRegistration && registered.isEmpty()) ||
            (registered.isNotEmpty() && !profile.canUnblock(code, PhysicalUnblockType.NFC))) {
            nfcScanMessage = "このNFCは登録されていません"
            return
        }
        // Home-initiated location pause must never invoke the strategy's stop action,
        // because some strategies would terminate the entire session.
        if (intent.getBooleanExtra(EXTRA_LOCATION_PAUSE_REQUEST, false)) {
            val allowed = sessionManager.pauseLocationWithVerifiedCode(
                profile.id, code, PhysicalUnblockType.NFC,
            )
            nfcScanMessage = if (allowed) "一時停止を開始したよ" else "このNFCでは一時停止できません"
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
        const val FULL_SCREEN_HOME_TARGET = "com.foqos.android.fullscreen.home"
        const val EXTRA_PACKAGE_NAME = "com.foqos.android.extra.BLOCKED_PACKAGE_NAME"
        const val EXTRA_DOMAIN = "com.foqos.android.extra.BLOCKED_DOMAIN"
        const val EXTRA_LOCATION_PAUSE_REQUEST = "com.foqos.android.extra.LOCATION_PAUSE_REQUEST"

        private val SOFT_UNBLOCK_STRATEGY_IDS = setOf(
            NfcSoftUnblockBlockingStrategy.ID,
            QrSoftUnblockBlockingStrategy.ID,
        )
    }
}
