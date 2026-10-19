package com.foqos.android.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.foqos.android.domain.session.SessionManager
import com.foqos.android.service.overlay.BlockOverlayActivity
import com.foqos.android.service.session.BlockingStateHolder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Accessibility services are instantiated by the system, not Hilt, so they can't use
 * `@AndroidEntryPoint` (there's no Activity/Fragment/View/Service-with-a-Hilt-base-class hook for
 * it here). This [dagger.hilt.EntryPoint] lets [ForegroundAppAccessibilityService] pull its
 * dependencies straight out of the singleton component once it is connected.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AccessibilityServiceEntryPoint {
    fun blockingStateHolder(): BlockingStateHolder
    fun blockPolicy(): AccessibilityBlockPolicy
    fun sessionManager(): SessionManager
}

/**
 * The primary (non-fallback) enforcement path: reads the foreground app's package from
 * accessibility events and, for known browsers, the address bar's text, then asks
 * [AccessibilityBlockPolicy] what to do about it. Mirrors iOS's `ManagedSettings` shielding, minus
 * the OS-level guarantee - see [com.foqos.android.service.session.UsageStatsPoller] for the
 * best-effort fallback used when this service is disabled.
 *
 * Declared with `canRetrieveWindowContent=true` in `res/xml/accessibility_service_config.xml` so
 * [readUrlBarText] can inspect the browser's address bar node; the config's `android:description`
 * discloses exactly (and only) that use to the user in system settings.
 */
class ForegroundAppAccessibilityService : AccessibilityService() {

    private lateinit var blockingStateHolder: BlockingStateHolder
    private lateinit var blockPolicy: AccessibilityBlockPolicy
    private lateinit var sessionManager: SessionManager
    private var connected = false
    private var lastLoggedSettingsClass: String? = null
    private var lastLoggedSettingsProbe: String? = null

    private var lastOverlayPackage: String? = null
    private var lastOverlayAtElapsedMillis: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, AccessibilityServiceEntryPoint::class.java)
        blockingStateHolder = entryPoint.blockingStateHolder()
        blockPolicy = entryPoint.blockPolicy()
        sessionManager = entryPoint.sessionManager()
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // An uncaught exception thrown from an accessibility service callback kills the whole
        // service binding - Android/Samsung then surfaces it as a "crashed service" that must be
        // manually re-toggled in Settings, silently ending enforcement mid-session. This handler
        // must never let an exception escape; log it and drop the event instead.
        try {
            handleAccessibilityEvent(event)
        } catch (t: Throwable) {
            Log.e(TAG, "onAccessibilityEvent failed - dropping this event, service stays alive", t)
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        val state = blockingStateHolder.state.value
        val neverBlock = AccessibilityBlockPolicy.neverBlockWithLauncher(currentLauncherPackageName())

        // Diagnostics only: never emit Settings window text or accessibility-node contents.
        // Log whether Foqos-specific markers are visible, so generic Settings activities can
        // be distinguished in a later, separately tested policy change.
        if (packageName in AccessibilityBlockPolicy.SETTINGS_PACKAGES) {
            val activityClass = event.className?.toString() ?: "<unknown>"
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                activityClass != lastLoggedSettingsClass
            ) {
                lastLoggedSettingsClass = activityClass
                Log.d(TAG, "Settings window diagnostic: package=$packageName class=$activityClass")
            }
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {
                val markers = settingsFoqosMarkers()
                val signature = "$activityClass:${markers.first}:${markers.second}"
                if (signature != lastLoggedSettingsProbe) {
                    lastLoggedSettingsProbe = signature
                    Log.d(
                        TAG,
                        "Settings Foqos markers: class=$activityClass " +
                            "nameVisible=${markers.first} packageVisible=${markers.second}",
                    )
                }
            }
        } else {
            lastLoggedSettingsClass = null
            lastLoggedSettingsProbe = null
        }

        val protectedSettingsScreen = state?.strictMode == true &&
            packageName in AccessibilityBlockPolicy.SETTINGS_PACKAGES &&
            isProtectedFoqosSettingsScreen()

        val decision = if (packageName in AccessibilityBlockPolicy.BROWSER_PACKAGES) {
            val url = readUrlBarText(packageName)
            blockPolicy.decideForUrl(packageName, url, state, neverBlockPackages = neverBlock)
        } else {
            blockPolicy.decideForApp(packageName, state, neverBlockPackages = neverBlock,
                protectedSettingsScreen = protectedSettingsScreen)
        }

        applyDecision(decision)
    }

    private fun applyDecision(decision: BlockDecision) {
        when (decision) {
            BlockDecision.Allow -> Unit
            is BlockDecision.BlockApp -> maybeLaunchOverlay(decision.packageName, domain = null)
            is BlockDecision.BlockDomain -> maybeLaunchOverlay(decision.packageName, domain = decision.domain)
            is BlockDecision.RedirectAway -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    /** Debounced so rapid duplicate window-state events for the same package don't restack overlays. */
    private fun maybeLaunchOverlay(packageName: String, domain: String?) {
        val now = SystemClock.elapsedRealtime()
        if (lastOverlayPackage == packageName && now - lastOverlayAtElapsedMillis < OVERLAY_DEBOUNCE_MILLIS) return
        lastOverlayPackage = packageName
        lastOverlayAtElapsedMillis = now

        val intent = Intent(this, BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
            if (domain != null) putExtra(BlockOverlayActivity.EXTRA_DOMAIN, domain)
        }
        startActivity(intent)
    }

    /**
     * Diagnostic presence checks only. Inspect at most 160 accessible nodes and log only
     * booleans, never screen contents. A visible app label is NOT sufficient evidence to
     * enforce a block; these results are for Pixel device investigation.
     */
    private fun settingsFoqosMarkers(): Pair<Boolean, Boolean> {
        val root = rootInActiveWindow ?: return false to false
        val pending = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        pending.add(root)
        var nameVisible = false
        var packageVisible = false
        var visited = 0
        while (pending.isNotEmpty() && visited < 160) {
            val node = pending.removeFirst()
            visited++
            val texts = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            for (value in texts) {
                if (value.trim().equals("Foqos", ignoreCase = true)) nameVisible = true
                if (value.contains(AccessibilityBlockPolicy.FOQOS_PACKAGE_NAME, ignoreCase = true)) {
                    packageVisible = true
                }
            }
            if (nameVisible && packageVisible) break
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { pending.add(it) }
            }
        }
        return nameVisible to packageVisible
    }

    /**
     * Restrict only Foqos-specific system pages. Require both the visible app label and
     * a page-specific control/title, never the label alone (which can appear in app lists).
     * This is a best-effort Pixel Settings heuristic, not an OS-level uninstall lock.
     */
    private fun isProtectedFoqosSettingsScreen(): Boolean {
        val root = rootInActiveWindow ?: return false
        val pending = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        pending.add(root)
        var hasFoqosLabel = false
        var hasAppManagementControls = false
        var hasAccessibilityHeading = false
        var visited = 0
        while (pending.isNotEmpty() && visited < 240) {
            val node = pending.removeFirst()
            visited++
            val texts = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            for (raw in texts) {
                val value = raw.trim()
                if (value.equals("Foqos", ignoreCase = true)) hasFoqosLabel = true
                if (value.equals("Uninstall", true) || value == "アンインストール" ||
                    value.equals("Force stop", true) || value == "強制停止"
                ) hasAppManagementControls = true
                if (value.equals("Accessibility", true) || value == "ユーザー補助" ||
                    value.equals("Accessibility settings", true)
                ) hasAccessibilityHeading = true
            }
            if (hasFoqosLabel && (hasAppManagementControls || hasAccessibilityHeading)) return true
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { pending.add(it) }
            }
        }
        return false
    }

    /** Reads the current text of [packageName]'s address bar, or null if it can't be found. */
    private fun readUrlBarText(packageName: String): String? {
        val viewId = URL_BAR_VIEW_IDS[packageName] ?: return null
        val root = rootInActiveWindow ?: return null
        return try {
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            try {
                nodes.firstOrNull { !it.text.isNullOrBlank() }?.text?.toString()
            } finally {
                nodes.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    private fun currentLauncherPackageName(): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "ForegroundA11yService"
        private const val OVERLAY_DEBOUNCE_MILLIS = 1_500L

        /** Per-browser address-bar view id, used with [android.view.accessibility.AccessibilityNodeInfo.findAccessibilityNodeInfosByViewId]. */
        val URL_BAR_VIEW_IDS: Map<String, String> = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.chrome.beta" to "com.chrome.beta:id/url_bar",
            "com.chrome.dev" to "com.chrome.dev:id/url_bar",
            "com.chrome.canary" to "com.chrome.canary:id/url_bar",
            "org.chromium.chrome" to "org.chromium.chrome:id/url_bar",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.firefox_beta" to "org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        )
    }
}
