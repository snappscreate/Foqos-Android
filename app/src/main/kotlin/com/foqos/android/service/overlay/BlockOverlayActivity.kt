package com.foqos.android.service.overlay

import android.content.Intent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (targetPackage.isNullOrBlank()) {
            finish()
            return
        }
        val targetDomain = intent.getStringExtra(EXTRA_DOMAIN)

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

                BlockScreenContent(
                    profileName = state?.profileName.orEmpty(),
                    blockedLabel = blockedLabel,
                    sessionStartEpochMillis = state?.sessionStartEpochMillis ?: System.currentTimeMillis(),
                    softUnblockInfo = softUnblockInfo,
                    onGoBack = { finishAndGoHome() },
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
