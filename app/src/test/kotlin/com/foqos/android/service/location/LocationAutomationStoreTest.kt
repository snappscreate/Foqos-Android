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
