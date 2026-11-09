package com.foqos.android.ui.activitylab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ActivityChallengeSetStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun orOfAndMovementSetsRequireAllMembersWithinOneSet() {
        val id = UUID.randomUUID().toString()
        val store = ActivityChallengeSetStore(context)
        val one = ActivityChallengeSet(conditionIds = listOf("A", "B"), pauseMinutes = 15)
        val two = ActivityChallengeSet(conditionIds = listOf("C"), pauseMinutes = 10)
        try {
            store.save(id, listOf(one, two))
            assertThat(store.chooseSet(id, "session-1", one.id)).isTrue()
            assertThat(store.complete(id, "session-1", "A"))
                .isEqualTo(ActivityProofResult.STEP_COMPLETED)
            assertThat(ActivityChallengeSetStore(context).nextConditions(id, "session-1"))
                .containsExactly("B")
            assertThat(store.complete(id, "session-1", "A"))
                .isEqualTo(ActivityProofResult.REJECTED)
            assertThat(store.complete(id, "wrong-session", "B"))
                .isEqualTo(ActivityProofResult.REJECTED)
            assertThat(store.complete(id, "session-1", "B"))
                .isEqualTo(ActivityProofResult.PAUSED)

            assertThat(store.chooseSet(id, "session-2", two.id)).isTrue()
            assertThat(store.complete(id, "session-2", "C"))
                .isEqualTo(ActivityProofResult.PAUSED)
        } finally { store.deleteProfile(id) }
    }

    @Test fun changingSetClearsPartialProgress() {
        val id = UUID.randomUUID().toString()
        val store = ActivityChallengeSetStore(context)
        val one = ActivityChallengeSet(conditionIds = listOf("A", "B"))
        val two = ActivityChallengeSet(conditionIds = listOf("C", "D"))
        try {
            store.save(id, listOf(one, two))
            store.chooseSet(id, "s", one.id)
            store.complete(id, "s", "A")
            store.chooseSet(id, "s", two.id)
            assertThat(store.progress(id, "s")?.completedIds).isEmpty()
            assertThat(store.complete(id, "s", "B"))
                .isEqualTo(ActivityProofResult.REJECTED)
        } finally { store.deleteProfile(id) }
    }
}
