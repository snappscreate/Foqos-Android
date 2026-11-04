package com.foqos.android.service.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GlobalSessionProtectionStoreTest {
    @Test fun `global protection settings survive a new store instance`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("global_session_protection", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            val config = GlobalSessionProtectionStore.Config(
                strictMode = true,
                blockAppInstallation = true,
                disableBackgroundStops = true,
                enableEmergencyUnblock = false,
            )
            GlobalSessionProtectionStore(context).save(config)
            val restored = GlobalSessionProtectionStore(context)
            assertThat(restored.isConfigured()).isTrue()
            assertThat(restored.read()).isEqualTo(config)
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
