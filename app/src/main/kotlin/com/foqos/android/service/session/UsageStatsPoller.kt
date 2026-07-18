package com.foqos.android.service.session

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.foqos.android.service.accessibility.AccessibilityBlockPolicy
import com.foqos.android.service.accessibility.BlockDecision
import com.foqos.android.service.overlay.BlockOverlayActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Best-effort enforcement fallback used by [SessionForegroundService] whenever the user hasn't (or
 * hasn't yet) granted the accessibility service - polls [UsageStatsManager] for the most recent
 * foreground-app event every [POLL_INTERVAL_MILLIS] and runs it through the same
 * [AccessibilityBlockPolicy] as [com.foqos.android.service.accessibility.ForegroundAppAccessibilityService].
 *
 * **Limitation**: unlike the accessibility path, this has no way to read a browser's address bar,
 * so per-domain blocking never fires here - only whole-app blocking. It also can't
 * `performGlobalAction(GLOBAL_ACTION_HOME)`, so a [BlockDecision.RedirectAway] (strict-mode
 * settings / uninstall deterrent) is enforced the same way as an app block: the overlay is shown
 * over the offending screen instead of silently returning home.
 */
@Singleton
class UsageStatsPoller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blockingStateHolder: BlockingStateHolder,
    private val blockPolicy: AccessibilityBlockPolicy,
) {

    private var job: Job? = null
    private var lastOverlayPackage: String? = null
    private var lastOverlayAtElapsedMillis: Long = 0L

    /** Starts polling on [scope]; a no-op if already running. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching { pollOnce() }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /** Stops polling; safe to call even if not running. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private fun pollOnce() {
        val packageName = mostRecentForegroundPackage() ?: return
        val state = blockingStateHolder.state.value
        val neverBlock = AccessibilityBlockPolicy.neverBlockWithLauncher(currentLauncherPackageName())

        // No URL detection in fallback mode - always decide at the app level, even for browsers.
        val decision = blockPolicy.decideForApp(packageName, state, neverBlockPackages = neverBlock)
        when (decision) {
            BlockDecision.Allow -> Unit
            is BlockDecision.BlockApp -> maybeLaunchOverlay(decision.packageName, domain = null)
            is BlockDecision.BlockDomain -> maybeLaunchOverlay(decision.packageName, decision.domain)
            is BlockDecision.RedirectAway -> maybeLaunchOverlay(packageName, domain = null)
        }
    }

    private fun mostRecentForegroundPackage(): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val end = System.currentTimeMillis()
        val start = end - QUERY_WINDOW_MILLIS
        val events = usageStatsManager.queryEvents(start, end) ?: return null

        var mostRecentPackage: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            if (isForegroundEvent) {
                mostRecentPackage = event.packageName
            }
        }
        return mostRecentPackage
    }

    private fun maybeLaunchOverlay(packageName: String, domain: String?) {
        val now = SystemClock.elapsedRealtime()
        if (lastOverlayPackage == packageName && now - lastOverlayAtElapsedMillis < OVERLAY_DEBOUNCE_MILLIS) return
        lastOverlayPackage = packageName
        lastOverlayAtElapsedMillis = now

        val intent = Intent(context, BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
            if (domain != null) putExtra(BlockOverlayActivity.EXTRA_DOMAIN, domain)
        }
        context.startActivity(intent)
    }

    private fun currentLauncherPackageName(): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
    }

    companion object {
        private const val QUERY_WINDOW_MILLIS = 2_000L
        const val POLL_INTERVAL_MILLIS = 700L
        private const val OVERLAY_DEBOUNCE_MILLIS = 1_500L
    }
}

/** Runtime-permission/settings checks needed before either enforcement path can actually work. */
object PermissionChecks {

    /** Whether the app has been granted the "usage access" special app op (needed by [UsageStatsPoller]). */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Whether [serviceClass] is currently enabled as an accessibility service. */
    fun isAccessibilityEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val enabledGlobally = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        if (!enabledGlobally) return false

        val expectedComponent = "${context.packageName}/${serviceClass.name}"
        val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabledServices.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }

    /** Whether the app can currently draw the overlay activity over other apps (SYSTEM_ALERT_WINDOW). */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
}
