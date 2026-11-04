package com.foqos.android.service.location

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocationAutomationStoreTest {
    @Test fun `two location rules persist and transitions remain independent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocationAutomationStore(context)
        store.clear()
        try {
            store.save(LocationRule("profile-a", 33.59, 130.40, 200f))
            store.save(LocationRule("profile-b", 33.61, 130.43, 350f))
            store.setLastInside("profile-a", true)
            store.setLastInside("profile-b", false)
            val restored = LocationAutomationStore(context)
            assertThat(restored.readAll().map { it.profileId }).containsExactly("profile-a", "profile-b")
            assertThat(restored.lastInside("profile-a")).isTrue()
            assertThat(restored.lastInside("profile-b")).isFalse()

            restored.clear("profile-a")
            assertThat(restored.read("profile-a")).isNull()
            assertThat(restored.read("profile-b")).isNotNull()
            assertThat(restored.lastInside("profile-b")).isFalse()
        } finally {
            store.clear()
        }
    }

    @Test fun `migrates old single rule and status without losing values`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocationAutomationStore(context)
        store.clear()
        val prefs = context.getSharedPreferences("location_automation", Context.MODE_PRIVATE)
        try {
            prefs.edit()
                .putString("profileId", "legacy")
                .putLong("latitude", java.lang.Double.doubleToRawLongBits(33.59))
                .putLong("longitude", java.lang.Double.doubleToRawLongBits(130.40))
                .putFloat("radius", 220f)
                .putBoolean("last_inside", true)
                .commit()
            val rule = LocationAutomationStore(context).read("legacy")
            assertThat(rule?.latitude).isEqualTo(33.59)
            assertThat(rule?.radiusMeters).isEqualTo(220f)
            assertThat(store.lastInside("legacy")).isTrue()
        } finally {
            store.clear()
        }
    }

    @Test fun `debug location override survives another store instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocationAutomationStore(context)
        store.clear()
        try {
            store.save(LocationRule("location-test-profile", 33.59, 130.40, 200f))
            store.setManualInside(false)
            assertThat(LocationAutomationStore(context).manualInside()).isFalse()
            assertThat(LocationAutomationStore(context).read()?.profileId).isEqualTo("location-test-profile")
            store.clearManualInside()
            assertThat(LocationAutomationStore(context).manualInside()).isNull()
        } finally {
            store.clear()
        }
    }
}
