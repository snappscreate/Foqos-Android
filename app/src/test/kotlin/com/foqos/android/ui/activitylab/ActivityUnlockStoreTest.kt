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
class ActivityUnlockStoreTest {
    private fun fresh() = ActivityUnlockStore(ApplicationProvider.getApplicationContext<Context>())
    private fun profileId() = UUID.randomUUID().toString()

    @Test fun savesReusableMovementConditionsWithoutPauseDurations() {
        val store = fresh()
        val id = profileId()
        val pushups = ActivityUnlockCondition(description = "腕立て伏せ30回", frontCamera = true)
        val cleaning = ActivityUnlockCondition(description = "部屋掃除", frontCamera = false)
        store.save(id, listOf(pushups, cleaning))
        assertThat(fresh().conditions(id)).containsExactly(pushups, cleaning).inOrder()
    }

    @Test fun subchallengeRewardIsPerProfileNotPartOfMovementDefinition() {
        val store = fresh()
        val first = profileId()
        val second = profileId()
        val movement = ActivityUnlockCondition(description = "スクワット50回")
        store.save(first, listOf(movement))
        store.save(second, listOf(movement))
        store.setConditionPauseMinutes(first, movement.id, 15)
        store.setConditionPauseMinutes(second, movement.id, 30)
        assertThat(fresh().conditionPauseMinutes(first, movement.id, 10)).isEqualTo(15)
        assertThat(fresh().conditionPauseMinutes(second, movement.id, 10)).isEqualTo(30)
        assertThat(fresh().conditions(first)).containsExactly(movement)
        assertThat(fresh().conditions(second)).containsExactly(movement)
        store.deleteProfile(first)
        store.deleteProfile(second)
    }

    @Test fun cannotChangeSelectedSubConditionWithinSession() {
        val store = fresh()
        val profile = profileId()
        val a = ActivityUnlockCondition(description = "腕立て伏せ30回")
        val b = ActivityUnlockCondition(description = "腹筋50回")
        store.save(profile, listOf(a,b))
        assertThat(store.select(profile, "session1", a.id)).isTrue()
        assertThat(store.select(profile, "session1", b.id)).isFalse()
        assertThat(fresh().selection(profile, "session1")?.conditionId).isEqualTo(a.id)
        store.clear(profile)
        assertThat(store.selection(profile, "session1")).isNull()
    }

    @Test fun mainVideoConditionIsSelectedWithoutPriorManualChoice() {
        val store = fresh()
        val profile = profileId()
        val a = ActivityUnlockCondition(description = "スクワット5回")
        store.save(profile, listOf(a))
        store.setMainCondition(profile, a.id)
        assertThat(fresh().selection(profile, "session1")?.conditionId).isEqualTo(a.id)
        assertThat(store.select(profile, "session1", a.id)).isTrue()
    }

    @Test fun staleSessionSelectionCannotCarryToNewSession() {
        val store = fresh()
        val profile = profileId()
        val a = ActivityUnlockCondition(description = "スクワット5回")
        store.save(profile, listOf(a))
        assertThat(store.select(profile, "old", a.id)).isTrue()
        assertThat(fresh().selection(profile, "new")).isNull()
    }

    @Test fun retainsDifferentPauseDurationForRestartRecovery() {
        val store = fresh()
        val id = UUID.randomUUID().toString()
        store.savePauseMinutes(id, 15)
        assertThat(fresh().pauseMinutes(id)).isEqualTo(15)
        store.clearPauseMinutes(id)
        assertThat(store.pauseMinutes(id)).isNull()
    }
}
