package com.foqos.android.ui.activitylab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActivityLabSettingsAndSquatTest {
    @Test fun `remember configured capture settings after recreating store`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("activity_lab_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        try {
            val expected = ActivityLabSettingsStore.Settings(
                activity = "腹筋をする",
                mode = "VIDEO",
                frontCamera = true,
                intervalMillis = 500L,
                durationSeconds = 30,
                geminiModel = "gemini-3.5-flash-lite",
                useRelay = true,
                endpoint = "https://example.workers.dev",
            )
            ActivityLabSettingsStore(context).save(expected)
            assertThat(ActivityLabSettingsStore(context).load()).isEqualTo(expected)
        } finally {
            context.getSharedPreferences("activity_lab_settings", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test fun `shallow bending without deep squat never completes a cycle`() {
        assertThat(SquatCycleCounter.count(listOf(1, -1, -1, 1, -1, 1))).isEqualTo(0)
    }

    @Test fun `standing then deep squat then standing counts one squat`() {
        assertThat(SquatCycleCounter.count(listOf(1, -1, 0, 0, -1, 1))).isEqualTo(1)
    }

    @Test fun `standing and bending with no return is not enough`() {
        assertThat(SquatCycleCounter.count(listOf(1, -1, 0, 0))).isEqualTo(0)
    }

    @Test fun `deep phases cannot be counted without an earlier standing pose`() {
        assertThat(SquatCycleCounter.count(listOf(0, -1, 0, 1))).isEqualTo(0)
    }
}
