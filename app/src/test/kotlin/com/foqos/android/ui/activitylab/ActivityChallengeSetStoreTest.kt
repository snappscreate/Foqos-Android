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

    @Test fun mainAndSubGroupsAreStoredAndVerifiedSeparately() {
        val id = UUID.randomUUID().toString()
        val store = ActivityChallengeSetStore(context)
        val main = ActivityChallengeSet(conditionIds = listOf("A", "B"), pauseMinutes = 20)
        val sub = ActivityChallengeSet(conditionIds = listOf("C", "D"), pauseMinutes = 15)
        try {
            store.save(id, listOf(main))
            store.saveSubSets(id, listOf(sub))
            assertThat(store.sets(id)).containsExactly(main)
            assertThat(store.subSets(id)).containsExactly(sub)
            assertThat(store.chooseSet(id, "sub-session", sub.id)).isTrue()
            assertThat(store.complete(id, "sub-session", "C"))
                .isEqualTo(ActivityProofResult.STEP_COMPLETED)
            assertThat(store.nextConditions(id, "sub-session")).containsExactly("D")
            assertThat(store.complete(id, "sub-session", "D"))
                .isEqualTo(ActivityProofResult.PAUSED)
            assertThat(store.chooseSet(id, "main-session", main.id)).isTrue()
            assertThat(store.complete(id, "main-session", "A"))
                .isEqualTo(ActivityProofResult.STEP_COMPLETED)
            assertThat(store.complete(id, "main-session", "B"))
                .isEqualTo(ActivityProofResult.PAUSED)
        } finally { store.deleteProfile(id) }
    }

    @Test fun savingEmptySubGroupsDoesNotReenableLegacySubConditions() {
        val id = UUID.randomUUID().toString()
        val store = ActivityChallengeSetStore(context)
        try {
            assertThat(store.hasSubSetConfig(id)).isFalse()
            store.saveSubSets(id, emptyList())
            assertThat(store.hasSubSetConfig(id)).isTrue()
            assertThat(store.subSets(id)).isEmpty()
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
