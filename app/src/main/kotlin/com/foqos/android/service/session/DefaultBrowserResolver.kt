package com.foqos.android.service.session

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Shared lookup so launcher and enforcement agree on the actual default browser package. */
object DefaultBrowserResolver {
    fun packageName(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            val holder = roles?.getRoleHolders(RoleManager.ROLE_BROWSER)?.firstOrNull()
            if (!holder.isNullOrBlank()) return holder
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            ?.takeUnless { it == "android" }
    }
}
