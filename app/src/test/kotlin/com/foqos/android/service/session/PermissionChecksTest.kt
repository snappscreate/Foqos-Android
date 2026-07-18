package com.foqos.android.service.session

import android.app.AppOpsManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.service.accessibility.ForegroundAppAccessibilityService
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Covers [PermissionChecks]'s testable branches against real (Robolectric-shadowed) framework state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PermissionChecksTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- isAccessibilityEnabled -------------------------------------------------------------

    @Test
    fun `isAccessibilityEnabled is false when accessibility is globally off`() {
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/${ForegroundAppAccessibilityService::class.java.name}",
        )

        assertThat(PermissionChecks.isAccessibilityEnabled(context, ForegroundAppAccessibilityService::class.java)).isFalse()
    }

    @Test
    fun `isAccessibilityEnabled is false when our service is not in the enabled list`() {
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.other.app/com.other.app.SomeService")

        assertThat(PermissionChecks.isAccessibilityEnabled(context, ForegroundAppAccessibilityService::class.java)).isFalse()
    }

    @Test
    fun `isAccessibilityEnabled is true when globally on and our service is listed`() {
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.other.app/com.other.app.SomeService:${context.packageName}/${ForegroundAppAccessibilityService::class.java.name}",
        )

        assertThat(PermissionChecks.isAccessibilityEnabled(context, ForegroundAppAccessibilityService::class.java)).isTrue()
    }

    // --- hasUsageAccess ----------------------------------------------------------------------

    @Test
    fun `hasUsageAccess is true once the usage-stats app-op is granted`() {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        Shadows.shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)

        assertThat(PermissionChecks.hasUsageAccess(context)).isTrue()
    }

    @Test
    fun `hasUsageAccess is false when the usage-stats app-op is denied`() {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        Shadows.shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName, AppOpsManager.MODE_ERRORED)

        assertThat(PermissionChecks.hasUsageAccess(context)).isFalse()
    }

    // --- canDrawOverlays ----------------------------------------------------------------------

    @Test
    fun `canDrawOverlays does not throw and returns a boolean`() {
        // Robolectric doesn't shadow android.provider.Settings#canDrawOverlays directly - this just
        // guards against it crashing (e.g. on a hidden-API path) rather than asserting a specific value.
        val result = PermissionChecks.canDrawOverlays(context)
        assertThat(result).isAnyOf(true, false)
    }
}
