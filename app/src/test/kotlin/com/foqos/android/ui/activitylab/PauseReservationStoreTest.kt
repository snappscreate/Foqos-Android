package com.foqos.android.ui.activitylab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PauseReservationStoreTest {
    @Test fun reservationIsPersistentAndBoundToSessionUntilCleared() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = UUID.randomUUID().toString()
        val store = PauseReservationStore(context)
        try {
            val reservation = PauseReservation("session-1", 1_700_000_000_000L, 195)
            store.save(profile, reservation)
            assertThat(PauseReservationStore(context).read(profile)).isEqualTo(reservation)
            store.clear(profile)
            assertThat(PauseReservationStore(context).read(profile)).isNull()
        } finally {
            store.clear(profile)
        }
    }
}
