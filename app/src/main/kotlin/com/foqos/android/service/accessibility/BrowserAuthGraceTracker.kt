package com.foqos.android.service.accessibility

/**
 * Grants only a short browser handoff from a genuinely permitted full-screen shortcut app.
 * Accessibility and UsageStats observers share the same process-local clock.
 *
 * Repeated events in the browser do not extend the deadline. A browser opened normally
 * has no grace. Other apps and Settings never inherit the exemption.
 */
internal class BrowserAuthGraceTracker(
    private val graceMillis: Long = 5_000L,
) {
    private var lastForegroundPackage: String? = null
    private var browserPackage: String? = null
    private var deadlineMillis: Long = 0L

    @Synchronized
    fun observe(
        packageName: String,
        browserPackages: Set<String>,
        allowedSourcePackages: Set<String>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val previous = lastForegroundPackage
        if (previous != packageName) {
            if (packageName in browserPackages &&
                previous != null && previous in allowedSourcePackages &&
                previous !in browserPackages
            ) {
                browserPackage = packageName
                deadlineMillis = nowMillis + graceMillis
            } else {
                browserPackage = null
                deadlineMillis = 0L
            }
            lastForegroundPackage = packageName
        }
        return packageName == browserPackage && nowMillis < deadlineMillis
    }

    @Synchronized
    fun clear() {
        lastForegroundPackage = null
        browserPackage = null
        deadlineMillis = 0L
    }
}

/** One clock for both foreground-app observers, rather than two independent grace periods. */
internal object BrowserAuthGrace {
    val tracker = BrowserAuthGraceTracker()
}
