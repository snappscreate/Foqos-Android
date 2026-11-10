package com.foqos.android.ui.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.service.location.LocationAutomationStore
import com.foqos.android.service.location.LocationRule
import com.foqos.android.ui.activitylab.ActivityChallengeSet
import com.foqos.android.ui.activitylab.ActivityChallengeSetStore
import com.foqos.android.ui.activitylab.ActivityUnlockCondition
import com.foqos.android.ui.activitylab.ActivityUnlockStore
import com.foqos.android.ui.activitylab.ProfileUnlockPolicyStore
import com.foqos.android.ui.activitylab.UnlockMethod
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileEditPendingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun allEditsRemainUnappliedBeforeExplicitCommit() {
        val id = UUID.randomUUID().toString()
        val pending = ProfileEditPending(context, BlockedProfile(id = id, name = "Draft"))
        val condition = ActivityUnlockCondition(description = "スクワット20回")
        val rule = LocationRule(id, 33.60, 130.42, 200f)
        pending.policy = pending.policy.copy(pause = UnlockMethod.ACTIVITY)
        pending.editLibrary(condition)
        pending.addActivity(condition)
        pending.mainConditionId = condition.id
        pending.sets = listOf(ActivityChallengeSet(conditionIds = listOf(condition.id), pauseMinutes = 30))
        pending.cooldownMinutes = 90
        pending.locationRule = rule
        pending.locationEnabled = true

        val policies = ProfileUnlockPolicyStore(context)
        val activities = ActivityUnlockStore(context)
        val sets = ActivityChallengeSetStore(context)
        val location = LocationAutomationStore(context)
        assertThat(policies.configured(id)).isFalse()
        assertThat(activities.conditions(id)).isEmpty()
        assertThat(sets.sets(id)).isEmpty()
        assertThat(location.read(id)).isNull()

        try {
            pending.commit()
            assertThat(policies.read(id).pause).isEqualTo(UnlockMethod.ACTIVITY)
            assertThat(policies.read(id).retryCooldownMinutes).isEqualTo(90)
            assertThat(sets.sets(id)).hasSize(1)
            assertThat(activities.mainConditionId(id)).isEqualTo(condition.id)
            // Location registration is committed by the parent profile Save, never by the editor.
            assertThat(location.read(id)).isNull()
        } finally {
            sets.deleteProfile(id)
            activities.deleteProfile(id)
            activities.deleteLibraryCondition(condition.id)
            policies.delete(id)
        }
    }
}
