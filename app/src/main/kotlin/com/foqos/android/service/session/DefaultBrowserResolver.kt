package com.foqos.android.service.session

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Shared lookup so launcher and enforcement agree on the default web handler. */
object DefaultBrowserResolver {
    fun packageName(context: Context): String? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)

        // resolveActivity uses the user's configured default when one exists.
        // RoleManager exposes role checks to regular apps, but not getRoleHolders.
        val resolved = context.packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName

        // A resolver/chooser is not itself a browser. When no default is configured,
        // prefer a real installed browser instead of launching the Android chooser,
        // which full-screen focus would rightly block.
        val valid = resolved?.takeUnless {
            it == "android" || it == "com.google.android.packageinstaller"
        }
        if (valid != null) return valid
        val installedHandlers = context.packageManager.queryIntentActivities(
            intent, PackageManager.MATCH_DEFAULT_ONLY
        ).map { it.activityInfo.packageName }.toSet()
        return listOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
        ).firstOrNull { it in installedHandlers }
            ?: installedHandlers.firstOrNull { it != "android" }

    }
}
