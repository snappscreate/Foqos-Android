package com.foqos.android.service.accessibility

/**
 * The browser's accessibility URL field may disappear while scrolling or rendering a page.
 * Reuse only a recently verified URL, only in the same browser/window, and never after
 * a different explicit address has been observed. This is not a permanent browser grant.
 */
internal class BrowserUrlContinuityTracker(
    private val maxMissingMillis: Long = 120_000L,
) {
    private var browserPackage: String? = null
    private var windowId: Int = -1
    private var lastVerifiedUrl: String? = null
    private var lastVerifiedAt: Long = 0L

    fun observe(
        packageName: String,
        windowId: Int,
        observedUrl: String?,
        verifiedAllowed: Boolean,
        nowMillis: Long,
    ): String? {
        if (packageName != browserPackage || windowId != this.windowId) {
            clear()
            browserPackage = packageName
            this.windowId = windowId
        }
        if (!observedUrl.isNullOrBlank()) {
            if (verifiedAllowed) {
                lastVerifiedUrl = observedUrl
                lastVerifiedAt = nowMillis
            } else {
                // Known disallowed URLs must never inherit a previous allowed site.
                lastVerifiedUrl = null
            }
            return observedUrl
        }
        return lastVerifiedUrl?.takeIf { nowMillis - lastVerifiedAt in 0..maxMissingMillis }
    }

    fun clear() {
        browserPackage = null
        windowId = -1
        lastVerifiedUrl = null
        lastVerifiedAt = 0L
    }
}
