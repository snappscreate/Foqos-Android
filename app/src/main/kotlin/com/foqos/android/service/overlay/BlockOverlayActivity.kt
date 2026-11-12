package com.foqos.android.service.overlay

import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.produceState
import com.foqos.android.data.model.BlockedProfile
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
import com.foqos.android.service.accessibility.AccessibilityBlockPolicy
import com.foqos.android.service.accessibility.BlockDecision
import com.foqos.android.service.session.PeriodicAccessSchedule
import com.foqos.android.service.session.PeriodicAccessStore
import com.foqos.android.ui.common.DeveloperUiVisibility
import com.foqos.android.ui.block.BlockScreenContent
import com.foqos.android.ui.activitylab.ActivityUnlockStore
import com.foqos.android.ui.activitylab.ActivityUnlockCondition
import com.foqos.android.ui.activitylab.ActivityUnlockActivity
import com.foqos.android.ui.block.FocusShortcut
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
    @Inject lateinit var blockPolicy: AccessibilityBlockPolicy

    @Inject lateinit var profileRepository: ProfileRepository

    @Inject lateinit var softUnblockRepository: SoftUnblockRepository

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var nfcDispatchManager: NfcForegroundDispatchManager

    private var nfcScanMessage by mutableStateOf<String?>(null)
    private var boundaryDistance by mutableStateOf<String?>(null)
    private var shieldResumed by mutableStateOf(false)
    private var configuredStopRequested by mutableStateOf(false)

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
                val allStates by blockingStateHolder.states.collectAsStateWithLifecycle()
                val fullScreenStore = remember { FullScreenModeStore(this@BlockOverlayActivity) }
                val scope = rememberCoroutineScope()
                val activityUnlockStore = remember { ActivityUnlockStore(this@BlockOverlayActivity) }
                val challengeSetStore = remember { com.foqos.android.ui.activitylab.ActivityChallengeSetStore(this@BlockOverlayActivity) }
                var showActivityChoices by remember { mutableStateOf(false) }
                var selectedActivityId by remember { mutableStateOf<String?>(null) }
                val locationStore = remember { LocationAutomationStore(this@BlockOverlayActivity) }
                val allLocationStates by remember { locationStore.observeAllStates() }
                    .collectAsStateWithLifecycle(initialValue = locationStore.readAll().associate { rule ->
                        rule.profileId to (rule to (locationStore.manualInside(rule.profileId)
                            ?: locationStore.lastInside(rule.profileId)))
                    })
                val requestedProfileId = intent.getStringExtra(EXTRA_PROFILE_ID)
                val candidates = allStates.values.map { entry ->
                    val browser = fullScreenStore.launchedBrowserPackage(entry.profileId)
                        ?: DefaultBrowserResolver.packageName(this@BlockOverlayActivity)
                    val configured = if (fullScreenStore.enabled(entry.profileId)) {
                        entry.copy(
                            fullScreenMode = true,
                            fullScreenAllowedPackages = fullScreenStore.allowedPackages(entry.profileId),
                            fullScreenAllowedUrls = fullScreenStore.allowedUrls(entry.profileId),
                            fullScreenBrowserPackage = browser,
                            fullScreenBrowserStartupUntil = browser?.let {
                                fullScreenStore.browserStartupUntil(entry.profileId, it)
                            } ?: 0L,
                        )
                    } else entry
                    configured.copy(
                        locationSuspended = configured.locationSuspended ||
                            (allLocationStates[configured.profileId]?.second == false),
                    )
                }
                val state = candidates.firstOrNull { requestedProfileId == it.profileId }
                    ?: candidates.firstOrNull { entry ->
                        val decision = if (targetDomain != null) {
                            blockPolicy.decideForUrl(targetPackage, "https://$targetDomain", entry)
                        } else blockPolicy.decideForApp(targetPackage, entry)
                        decision != BlockDecision.Allow
                    } ?: candidates.firstOrNull()
                val locationStatus = state?.profileId?.let { allLocationStates[it] }
                val fullScreen = state?.fullScreenMode == true
                val allowedPackages = state?.profileId
                    ?.let(fullScreenStore::allowedPackages).orEmpty()
                val defaultBrowser = DefaultBrowserResolver.packageName(this@BlockOverlayActivity)
                val allowedUrls = state?.profileId
                    ?.let(fullScreenStore::allowedUrls).orEmpty()
                var showAllowedApps by remember { mutableStateOf(false) }
                var showFeaturedPicker by remember { mutableStateOf(false) }
                var featuredShortcuts by remember(state?.profileId, allowedPackages, allowedUrls) {
                    mutableStateOf(state?.profileId?.let(fullScreenStore::featuredShortcuts).orEmpty())
                }
                // A featured URL must still be allowed, and phone is always safe to launch.
                val availableShortcuts = FocusShortcut.all(allowedPackages, allowedUrls)
                val launchableFeatured = featuredShortcuts.intersect(availableShortcuts).filter { id ->
                    when {
                        id == FocusShortcut.PHONE -> true
                        id.startsWith(FocusShortcut.URL_PREFIX) ->
                            DefaultBrowserResolver.packageName(this@BlockOverlayActivity) != null
                        id.startsWith(FocusShortcut.APP_PREFIX) ->
                            packageManager.getLaunchIntentForPackage(
                                id.removePrefix(FocusShortcut.APP_PREFIX)
                            ) != null
                        else -> false
                    }
                }
                fun openApprovedUrl(url: String) {
                    val profileId = state?.profileId
                    val resolved = DefaultBrowserResolver.packageName(this@BlockOverlayActivity)
                    if (profileId == null || resolved == null || url !in allowedUrls) {
                        nfcScanMessage = "許可されたURLを開けませんでした"
                        return
                    }
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setPackage(resolved)
                    fullScreenStore.grantBrowserStartup(profileId, resolved)
                    runCatching { startActivity(browserIntent); finish() }
                        .onFailure { nfcScanMessage = "ブラウザを起動できませんでした" }
                }
                fun openShortcut(id: String) {
                    val targetPkg = when {
                        id.startsWith(FocusShortcut.APP_PREFIX) -> id.removePrefix(FocusShortcut.APP_PREFIX)
                        id.startsWith(FocusShortcut.URL_PREFIX) -> defaultBrowser
                        else -> null
                    }
                    val targetUrl = id.takeIf { it.startsWith(FocusShortcut.URL_PREFIX) }
                        ?.removePrefix(FocusShortcut.URL_PREFIX)
                    val owner = if (targetPkg != null) candidates.firstOrNull { candidate ->
                        val decision = if (targetUrl != null)
                            blockPolicy.decideForUrl(targetPkg, targetUrl, candidate)
                        else blockPolicy.decideForApp(targetPkg, candidate)
                        decision != BlockDecision.Allow
                    } else null
                    if (owner != null && targetPkg != null) {
                        // Keep the icon visible in B; opening it takes the user to A's blocker.
                        startActivity(Intent(this@BlockOverlayActivity, BlockOverlayActivity::class.java).apply {
                            putExtra(EXTRA_PACKAGE_NAME, targetPkg)
                            putExtra(EXTRA_PROFILE_ID, owner.profileId)
                            if (targetUrl != null) putExtra(EXTRA_DOMAIN,
                                Uri.parse(targetUrl).host.orEmpty())
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        })
                        return
                    }
                    when {
                        id == FocusShortcut.PHONE -> {
                            runCatching {
                                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
                                finish()
                            }.onFailure { nfcScanMessage = "電話を起動できませんでした" }
                        }
                        id.startsWith(FocusShortcut.URL_PREFIX) ->
                            openApprovedUrl(id.removePrefix(FocusShortcut.URL_PREFIX))
                        id.startsWith(FocusShortcut.APP_PREFIX) -> {
                            val pkg = id.removePrefix(FocusShortcut.APP_PREFIX)
                            if (pkg !in allowedPackages) return
                            val launch = packageManager.getLaunchIntentForPackage(pkg)
                            if (launch != null) runCatching {
                                startActivity(launch); finish()
                            }.onFailure { nfcScanMessage = "アプリを起動できませんでした" }
                        }
                    }
                }
                var autoOpenCountdown by remember(state?.sessionId, targetPackage) {
                    mutableIntStateOf(10)
                }
                LaunchedEffect(
                    state?.sessionId, targetPackage, shieldResumed,
                    showFeaturedPicker, showAllowedApps, launchableFeatured,
                ) {
                    // Preserve remaining seconds while panels are open; resume on close.
                    if (!fullScreen || !shieldResumed || locationPauseRequest ||
                        showFeaturedPicker || showAllowedApps || launchableFeatured.isEmpty()) {
                        return@LaunchedEffect
                    }
                    while (autoOpenCountdown > 0) {
                        delay(1_000L)
                        autoOpenCountdown -= 1
                    }
                    val selection = launchableFeatured.randomOrNull() ?: return@LaunchedEffect
                    val profileId = state?.profileId ?: return@LaunchedEffect
                    if (selection !in fullScreenStore.featuredShortcuts(profileId)) return@LaunchedEffect
                    if (!FocusShortcut.valid(selection,
                            fullScreenStore.allowedPackages(profileId),
                            fullScreenStore.allowedUrls(profileId))) return@LaunchedEffect
                    openShortcut(selection)
                }

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
                    if (!locationPauseRequest) {
                        val blockedByAny = candidates.any { candidate ->
                            val candidateDecision = if (targetDomain != null)
                                blockPolicy.decideForUrl(targetPackage, "https://$targetDomain", candidate, periodicNow)
                            else blockPolicy.decideForApp(targetPackage, candidate, periodicNow)
                            candidateDecision != BlockDecision.Allow
                        }
                        if (!blockedByAny) {
                            if (fullScreen) finishAndGoHome() else finish()
                            return@LaunchedEffect
                        }
                    }
                    if (current == null) {
                        finishAndGoHome()
                        return@LaunchedEffect
                    }
                    // Full-screen focus pause must return to the real Android launcher.
                    // Simply finish() can reveal an underlying blocked app instead.
                    val outside = locationStatus?.second == false
                    val periodicOpen = PeriodicAccessSchedule.isActive(
                        PeriodicAccessStore.Config(current.periodicWaitMinutes, current.periodicAccessMinutes),
                        current.periodicAnchorEpochMillis, current.periodicActivatedAtEpochMillis, periodicNow,
                    )
                    if (current.pauseActive || current.breakActive || periodicOpen || current.locationSuspended || outside) {
                        // Another profile may still block this target. Re-select it instead
                        // of dismissing the entire shield after this profile pauses.
                        val stillBlocked = candidates.any { candidate ->
                            val candidateDecision = if (targetDomain != null)
                                blockPolicy.decideForUrl(targetPackage, "https://$targetDomain", candidate, periodicNow)
                            else blockPolicy.decideForApp(targetPackage, candidate, periodicNow)
                            candidateDecision != BlockDecision.Allow
                        }
                        if (!stillBlocked) {
                            if (fullScreen) finishAndGoHome() else finish()
                        }
                        return@LaunchedEffect
                    }
                    // A soft grant from A cannot dismiss the shield while B still blocks.
                    val grantExpiry = current.activeGrants[targetPackage]
                    if (grantExpiry != null && grantExpiry > periodicNow) {
                        val otherBlocks = candidates.any { candidate ->
                            val result = if (targetDomain != null)
                                blockPolicy.decideForUrl(targetPackage, "https://$targetDomain", candidate, periodicNow)
                            else blockPolicy.decideForApp(targetPackage, candidate, periodicNow)
                            result != BlockDecision.Allow
                        }
                        if (!otherBlocks) finish()
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
                    locationPauseRequest -> "一時休止の認証"
                    targetPackage == FULL_SCREEN_HOME_TARGET -> "集中モード"
                    else -> targetDomain ?: rememberAppLabel(targetPackage)
                }
                val activeProfile by produceState<BlockedProfile?>(
                    initialValue = null, key1 = state?.profileId,
                ) {
                    value = state?.profileId?.let { profileRepository.getById(it) }
                }
                val activityConditions = remember(activeProfile?.id) {
                    activeProfile?.id?.let(activityUnlockStore::conditions).orEmpty()
                }
                val activitySessionId = state?.sessionId.orEmpty()
                val activityProfileId = state?.profileId.orEmpty()
                val pauseMethod = com.foqos.android.ui.activitylab.ProfileUnlockPolicyStore(
                    this@BlockOverlayActivity).read(activityProfileId).pause
                val configuredChallengeSets =
                    (if (pauseMethod == com.foqos.android.ui.activitylab.UnlockMethod.ACTIVITY)
                        challengeSetStore.sets(activityProfileId) else emptyList()) +
                        challengeSetStore.subSets(activityProfileId)
                val legacyActivityAllowed =
                    !challengeSetStore.hasMainSetConfig(activityProfileId) &&
                    !challengeSetStore.hasSubSetConfig(activityProfileId)
                val challengeProgress = challengeSetStore.progress(activityProfileId, activitySessionId)
                val persistentActivitySelection = remember(activityProfileId, activitySessionId, selectedActivityId) {
                    activityUnlockStore.selection(activityProfileId, activitySessionId)
                }
                val selectedActivity = activityConditions.firstOrNull {
                    it.id == persistentActivitySelection?.conditionId
                }
                val explicitActivitySelection =
                    activityUnlockStore.hasExplicitSelection(activityProfileId, activitySessionId)
                val retryRemainingMillis = activityProfileId.takeIf { it.isNotBlank() }
                    ?.let(sessionManager::pauseRetryRemainingMillis) ?: 0L
                fun openActivityChallenge(condition: ActivityUnlockCondition) {
                    if (activityProfileId.isBlank() || activitySessionId.isBlank()) return
                    if (sessionManager.pauseRetryRemainingMillis(activityProfileId) > 0L) {
                        nfcScanMessage = "一時休止の再挑戦は待機時間終了後にできるよ"
                        return
                    }
                    val chosen = if (configuredChallengeSets.isEmpty()) {
                        activityUnlockStore.select(activityProfileId, activitySessionId, condition.id)
                    } else {
                        condition.id in challengeSetStore.nextConditions(activityProfileId, activitySessionId)
                    }
                    if (!chosen) return
                    selectedActivityId = condition.id
                    showActivityChoices = false
                    startActivity(Intent(this@BlockOverlayActivity, ActivityUnlockActivity::class.java).apply {
                        putExtra(ActivityUnlockActivity.EXTRA_PROFILE_ID, activityProfileId)
                        putExtra(ActivityUnlockActivity.EXTRA_SESSION_ID, activitySessionId)
                        putExtra(ActivityUnlockActivity.EXTRA_CONDITION_ID, condition.id)
                    })
                }
                val independentPolicy = activeProfile?.let {
                    com.foqos.android.ui.activitylab.ProfileUnlockPolicyStore(this@BlockOverlayActivity)
                        .takeIf { store -> store.configured(it.id) }?.read(it.id)
                }
                val nfcReady = if (independentPolicy != null) {
                    activeProfile?.physicalUnblockItems?.any {
                        it.type == PhysicalUnblockType.NFC
                    } == true && (
                        independentPolicy.stop == com.foqos.android.ui.activitylab.UnlockMethod.NFC ||
                        (selectedActivity == null &&
                            independentPolicy.pause == com.foqos.android.ui.activitylab.UnlockMethod.NFC)
                    )
                } else {
                    selectedActivity == null && (activeProfile?.let { profile ->
                        StrategyRegistry.byId(profile.strategyId).usesNfc ||
                            (locationPauseRequest && profile.physicalUnblockItems.any {
                                it.type == PhysicalUnblockType.NFC
                            })
                    } ?: false)
                }

                BlockScreenContent(
                    configuredStopAvailable = independentPolicy?.stop ==
                        com.foqos.android.ui.activitylab.UnlockMethod.NFC,
                    configuredStopSelected = configuredStopRequested,
                    onSelectConfiguredStop = { configuredStopRequested = !configuredStopRequested },
                    profileName = state?.profileName.orEmpty(),
                    blockedLabel = blockedLabel,
                    sessionStartEpochMillis = state?.sessionStartEpochMillis ?: System.currentTimeMillis(),
                    softUnblockInfo = softUnblockInfo,
                    periodicPauseAvailable = state?.let { current ->
                        PeriodicAccessSchedule.isAvailable(
                            PeriodicAccessStore.Config(current.periodicWaitMinutes, current.periodicAccessMinutes),
                            current.periodicAnchorEpochMillis, current.periodicActivatedAtEpochMillis, periodicNow,
                        ) && !PeriodicAccessSchedule.isActive(
                            PeriodicAccessStore.Config(current.periodicWaitMinutes, current.periodicAccessMinutes),
                            current.periodicAnchorEpochMillis, current.periodicActivatedAtEpochMillis, periodicNow,
                        ) && !current.pauseActive && !current.breakActive
                    } == true,
                    periodicPauseRemainingSeconds = state?.let { current ->
                        current.periodicAccessMinutes.toLong() * 60L
                    } ?: 0L,
                    onActivatePeriodicPause = {
                        val profileId = state?.profileId
                        if (profileId != null) scope.launch {
                            sessionManager.activatePeriodicAccess(profileId)
                        }
                    },
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
                    nfcInstruction = if (retryRemainingMillis > 0L && !configuredStopRequested)
                        "一時休止の再挑戦まであと${(retryRemainingMillis + 59_999L) / 60_000L}分"
                    else independentPolicy?.let { policy ->
                        when {
                            configuredStopRequested -> "停止用NFCをかざすとブロックを完全停止"
                            policy.pause == com.foqos.android.ui.activitylab.UnlockMethod.NFC ->
                                "NFCをかざすと設定時間だけ一時休止"
                            policy.stop == com.foqos.android.ui.activitylab.UnlockMethod.NFC ->
                                "NFCをかざすとブロックを完全停止"
                            else -> null
                        }
                    },
                    distanceDescription = boundaryDistance,
                    onSimulateNfc = if (BuildConfig.DEBUG && DeveloperUiVisibility.showDiagnosticControls && nfcReady) ({
                        lifecycleScope.launch {
                            val profile = activeProfile
                            val session = sessionManager.activeSessions.value.firstOrNull { it.profileId == profile?.id }
                            if (profile != null && session != null) {
                                val code = profile.physicalUnblockItems.firstOrNull {
                                    it.type == PhysicalUnblockType.NFC
                                }?.codeValue ?: "foqos-debug-virtual-tag"
                                processNfcCode(code)
                            }
                        }
                    }) else null,
                    onActivityChallenge = if (activityConditions.isNotEmpty() &&
                        (configuredChallengeSets.isNotEmpty() || legacyActivityAllowed) &&
                        retryRemainingMillis == 0L &&
                        !state.let { it == null || it.pauseActive || it.breakActive }) ({
                        if (configuredChallengeSets.isNotEmpty()) {
                            showActivityChoices = true
                        } else if (selectedActivity != null) {
                            openActivityChallenge(selectedActivity)
                        } else {
                            showActivityChoices = true
                        }
                    }) else null,
                    selectedActivityLabel = if (legacyActivityAllowed) selectedActivity?.description else null,
                    onChooseSubActivity =
                        if (legacyActivityAllowed && configuredChallengeSets.isEmpty() && selectedActivity != null && !explicitActivitySelection &&
                            activityConditions.any { it.id != selectedActivity.id }) ({
                            showActivityChoices = true
                        }) else null,
                    onGoBack = { finishAndGoHome() },
                    onOpenFoqosHome = { openFoqosHome() },
                    fullScreenMode = fullScreen || locationPauseRequest,
                    fullScreenShortcuts = if (fullScreen) ({
                        FullScreenShortcutGrid(
                            packages = allowedPackages,
                            urls = allowedUrls,
                            featuredShortcuts = featuredShortcuts,
                            autoOpenSeconds = if (shieldResumed && !showFeaturedPicker && launchableFeatured.isNotEmpty()) autoOpenCountdown else null,
                            editingFeatured = showFeaturedPicker,
                            onEditFeatured = {
                                showFeaturedPicker = !showFeaturedPicker
                                if (showFeaturedPicker) showAllowedApps = false
                            },
                            onFeaturedChange = { selected ->
                                val profileId = state?.profileId
                                if (profileId != null) {
                                    fullScreenStore.saveFeaturedShortcuts(profileId, selected)
                                    featuredShortcuts = fullScreenStore.featuredShortcuts(profileId)
                                }
                            },
                            allowedAppsExpanded = showAllowedApps,
                            onToggleAllowedApps = {
                                showAllowedApps = !showAllowedApps
                                if (showAllowedApps) showFeaturedPicker = false
                            },
                            appLabel = { pkg ->
                                runCatching {
                                    val info = packageManager.getApplicationInfo(pkg, 0)
                                    packageManager.getApplicationLabel(info).toString()
                                }.getOrDefault(pkg)
                            },
                            onPhone = { openShortcut(FocusShortcut.PHONE) },
                            onApp = { pkg -> openShortcut(FocusShortcut.app(pkg)) },
                            onUrl = { url -> openShortcut(FocusShortcut.url(url)) },
                        )
                    }) else null,
                    onOpenAllowedApps = null,
                    onOpenAndroidSettings = if (fullScreen) ({
                        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }) else null,
                    onOpenTemporarily = {
                        scope.launch {
                            sessionManager.issueSoftUnblockGrant(targetPackage, state?.profileId)
                        }
                    },
                )

                if (showActivityChoices && (configuredChallengeSets.isNotEmpty() || !explicitActivitySelection) && activityConditions.isNotEmpty()) {
                    AlertDialog(
                        onDismissRequest = { showActivityChoices = false },
                        title = { Text("今回の一時休止条件を選択") },
                        text = {
                            Column(modifier = Modifier.heightIn(max = 440.dp)
                                .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (configuredChallengeSets.isNotEmpty()) {
                                    Text("セット内の動作はすべて達成。セットを選び、残りの動作を進めてね。")
                                    configuredChallengeSets.forEachIndexed { index, group ->
                                        val completed = if (challengeProgress?.setId == group.id)
                                            challengeProgress.completedIds else emptySet()
                                        val kind = if (challengeSetStore.subSets(activityProfileId).any { it.id == group.id })
                                            "サブ" else "メイン"
                                        Text("$kind セット ${index+1}：${completed.size}/${group.conditionIds.size}達成 / 全達成で${group.pauseMinutes}分休止",
                                            style = MaterialTheme.typography.titleSmall)
                                        if (challengeProgress?.setId != group.id) {
                                            OutlinedButton(onClick = {
                                                challengeSetStore.chooseSet(activityProfileId, activitySessionId, group.id)
                                                selectedActivityId = group.id
                                                showActivityChoices = false
                                                showActivityChoices = true
                                            }) { Text("このセットを選択") }
                                        } else {
                                            group.conditionIds.filterNot { it in completed }.forEach { id ->
                                                activityConditions.firstOrNull { it.id == id }?.let { move ->
                                                    Button(onClick = { openActivityChallenge(move) },
                                                        modifier = Modifier.fillMaxWidth()) {
                                                        Text("挑戦：${move.description}")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                Text("選択すると、一時休止が成功するまでNFCには戻せないよ。")
                                activityConditions.filter {
                                    it.id != selectedActivity?.id
                                }.forEach { condition ->
                                    OutlinedButton(
                                        onClick = { openActivityChallenge(condition) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        val policyMinutes = independentPolicy?.pauseMinutes ?: 20
                                        val pauseDuration = if (activityUnlockStore.mainConditionId(activityProfileId) == condition.id)
                                            policyMinutes
                                        else activityUnlockStore.conditionPauseMinutes(
                                            activityProfileId, condition.id, policyMinutes)
                                        Text("${condition.description} / ${pauseDuration}分")
                                    }
                                }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showActivityChoices = false }) {
                                Text("キャンセル")
                            }
                        },
                    )
                }
                BackHandler { if (!fullScreen) finishAndGoHome() }
            }
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        val previous = intent
        setIntent(newIntent)
        // This activity is singleTask: a new blocked app/profile reuses the same instance.
        // Recreate only for an actually different target so the shield never keeps stale
        // shortcuts, NFC owner or blocked-app label.
        val changed = listOf(EXTRA_PACKAGE_NAME, EXTRA_DOMAIN, EXTRA_PROFILE_ID).any {
            previous.getStringExtra(it) != newIntent.getStringExtra(it)
        } || previous.getBooleanExtra(EXTRA_LOCATION_PAUSE_REQUEST, false) !=
            newIntent.getBooleanExtra(EXTRA_LOCATION_PAUSE_REQUEST, false)
        if (changed) recreate()
    }

    override fun onResume() {
        super.onResume()
        shieldResumed = true
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
        shieldResumed = false
        runCatching { NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this) }
        super.onPause()
    }

    private suspend fun processNfcCode(code: String) {
        val active = sessionManager.activeSessions.value
        val requested = intent.getStringExtra(EXTRA_PROFILE_ID)
        val matchingCredential = active.firstOrNull { candidate ->
            val profile = profileRepository.getById(candidate.profileId)
            profile?.physicalUnblockItems?.any {
                it.type == PhysicalUnblockType.NFC && profile.canUnblock(code, PhysicalUnblockType.NFC)
            } == true
        }
        if (active.size > 1 && requested == null && matchingCredential == null) {
            // Never guess which running profile an unrecognized NFC tag should release.
            nfcScanMessage = "登録されていないNFCです"
            return
        }
        val session = (requested?.let { id -> active.firstOrNull { it.profileId == id } }
            ?: matchingCredential
            ?: active.firstOrNull { candidate ->
                blockingStateHolder.stateForProfile(candidate.profileId)?.let {
                    it.profileId == candidate.profileId
                } == true
            }) ?: return
        val profile = profileRepository.getById(session.profileId) ?: return
        val independent = com.foqos.android.ui.activitylab.ProfileUnlockPolicyStore(this)
        if (independent.configured(profile.id)) {
            val policy = independent.read(profile.id)
            val forPause = !configuredStopRequested &&
                (intent.getBooleanExtra(EXTRA_LOCATION_PAUSE_REQUEST, false) ||
                    policy.pause == com.foqos.android.ui.activitylab.UnlockMethod.NFC)
            val approved = sessionManager.authorizeConfiguredCode(
                profile.id, code, PhysicalUnblockType.NFC, forPause = forPause,
            )
            nfcScanMessage = if (approved) {
                if (forPause) "一時休止したよ" else "ブロックを停止したよ"
            } else "このNFCは現在の条件に一致しないよ"
            return
        }
        if (ActivityUnlockStore(this).selection(profile.id, session.id) != null) {
            nfcScanMessage = "動作チャレンジ選択中。NFCへの変更はできないよ"
            return
        }
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
            nfcScanMessage = if (allowed) "一時休止を開始したよ" else "このNFCでは一時休止できません"
            return
        }
        // Existing strategy performs the final NFC permission and pause-only checks.
        when (val outcome = StrategyRegistry.byId(profile.strategyId).onStopInput(
            profile, session, StrategyInput.Code(code, StrategyInput.CodeSource.NFC), sessionManager
        )) {
            SessionOutcome.Paused -> nfcScanMessage = "一時休止を開始したよ"
            SessionOutcome.Stopped -> nfcScanMessage = "ブロックを解除したよ"
            is SessionOutcome.Rejected -> nfcScanMessage =
                if (registered.isNotEmpty()) "このNFCは登録されていません、または現在は使用できません"
                else outcome.reason
            else -> nfcScanMessage = "NFCを読み取ったよ"
        }
    }

    private fun refreshBoundaryDistance() {
        val requested = intent.getStringExtra(EXTRA_PROFILE_ID)
        val rule = requested?.let { LocationAutomationStore(this).read(it) }
            ?: LocationAutomationStore(this).read() ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            AndroidPackageManager.PERMISSION_GRANTED
        ) return
        @Suppress("MissingPermission")
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null && sessionManager.activeSessions.value.any { it.profileId == rule.profileId }) {
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
        const val EXTRA_PROFILE_ID = "com.foqos.android.extra.PROFILE_ID"

        private val SOFT_UNBLOCK_STRATEGY_IDS = setOf(
            NfcSoftUnblockBlockingStrategy.ID,
            QrSoftUnblockBlockingStrategy.ID,
        )
    }
}
