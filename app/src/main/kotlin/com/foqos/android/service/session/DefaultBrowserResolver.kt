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

        // A resolver/chooser is not itself a browser. Do not grant it web access.
        return resolved?.takeUnless {
            it == "android" || it == "com.google.android.packageinstaller"
        }
    }
}
