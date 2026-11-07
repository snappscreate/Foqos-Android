package com.foqos.android.service.accessibility

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process

/**
 * Only user-launchable applications (and home launchers) are eligible for an app shield.
 * Transient internal helpers, permission dialogs, credential providers and other background
 * packages must not be mistaken for a user switching to another blocked app.
 *
 * Strict-mode Settings/installer protection is a separate decision and is not filtered here.
 */
internal object LauncherVisibleAppFilter {
    fun isEligible(context: Context, packageName: String, homePackage: String?): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == homePackage ||
            packageName in AccessibilityBlockPolicy.LAUNCHER_PACKAGES
        ) return true

        // LauncherApps is the same surface the system launcher uses. This works better
        // than merely checking whether an arbitrary package has an installed Activity.
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val visibleInLauncher = runCatching {
            launcherApps?.getActivityList(packageName, Process.myUserHandle())
                ?.isNotEmpty() == true
        }.getOrDefault(false)
        if (visibleInLauncher) return true

        // Fallback for OEM implementations that hide LauncherApps results.
        return runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName) != null
        }.getOrDefault(false)
    }
}
