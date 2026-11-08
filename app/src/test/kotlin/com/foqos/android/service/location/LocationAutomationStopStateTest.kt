package com.foqos.android.service.location

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
class LocationAutomationStopStateTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `real geofence transition clearing debug override never clears complete stop`() {
        val profileId = UUID.randomUUID().toString()
        val store = LocationAutomationStore(context)
        try {
            store.save(LocationRule(profileId, 33.60, 130.42, 200f))
            store.setManualInside(profileId, false)
            store.setManuallyStopped(profileId, true)

            // GeofenceTransitionReceiver executes this before an ENTER event.
            store.clearManualInside(profileId)
            store.setLastInside(profileId, true)

            assertThat(store.manualInside(profileId)).isNull()
            assertThat(store.lastInside(profileId)).isTrue()
            assertThat(store.manuallyStopped(profileId)).isTrue()

            // Re-registering/editing the geofence must preserve the user's stop.
            store.save(LocationRule(profileId, 33.61, 130.43, 250f))
            assertThat(LocationAutomationStore(context).manuallyStopped(profileId)).isTrue()
        } finally {
            store.clear(profileId)
        }
    }

    @Test
    fun `disabling automation keeps saved area and complete stop state`() {
        val profileId = UUID.randomUUID().toString()
        val store = LocationAutomationStore(context)
        try {
            val area = LocationRule(profileId, 33.60, 130.42, 200f)
            store.save(area)
            store.setManuallyStopped(profileId, true)
            assertThat(store.enabled(profileId)).isTrue()
            store.setEnabled(profileId, false)
            assertThat(store.enabled(profileId)).isFalse()
            assertThat(store.read(profileId)).isEqualTo(area)
            assertThat(store.manuallyStopped(profileId)).isTrue()
            store.setEnabled(profileId, true)
            assertThat(store.manuallyStopped(profileId)).isTrue()
        } finally {
            store.clear(profileId)
        }
    }

    @Test
    fun `clearing a deleted geofence removes its previous stop state`() {
        val profileId = UUID.randomUUID().toString()
        val store = LocationAutomationStore(context)
        store.save(LocationRule(profileId, 33.60, 130.42, 200f))
        store.setManuallyStopped(profileId, true)
        store.clear(profileId)
        assertThat(store.manuallyStopped(profileId)).isFalse()
    }
}
