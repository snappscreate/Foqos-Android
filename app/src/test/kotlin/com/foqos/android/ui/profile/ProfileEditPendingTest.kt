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

    @Test fun libraryRegistrationAndEditsSurviveDiscardedProfileDraft() {
        val profileId = UUID.randomUUID().toString()
        val activity = ActivityUnlockCondition(description = "部屋の掃除")
        val library = ActivityUnlockStore(context)
        val groups = ActivityChallengeSetStore(context)
        val draft = ProfileEditPending(context, BlockedProfile(id = profileId, name = "Unsaved"))
        try {
            draft.editLibrary(activity)
            assertThat(library.reusableConditions().firstOrNull { it.id == activity.id }
                ?.description).isEqualTo("部屋の掃除")
            draft.editLibrary(activity.copy(description = "部屋を30分掃除"))
            draft.sets = listOf(ActivityChallengeSet(conditionIds = listOf(activity.id)))
            // No parent Save: the reusable definition persists, but set membership does not.
            val reopened = ProfileEditPending(context, BlockedProfile(id = profileId, name = "Unsaved"))
            assertThat(reopened.library.firstOrNull { it.id == activity.id }?.description)
                .isEqualTo("部屋を30分掃除")
            assertThat(groups.sets(profileId)).isEmpty()
            assertThat(library.conditions(profileId)).isEmpty()
            assertThat(draft.removeFromLibrary(activity.id)).isFalse()
            assertThat(library.reusableConditions().any { it.id == activity.id }).isTrue()
            draft.sets = emptyList()
            assertThat(draft.removeFromLibrary(activity.id)).isTrue()
            assertThat(library.reusableConditions().any { it.id == activity.id }).isFalse()
        } finally {
            groups.deleteProfile(profileId)
            library.deleteProfile(profileId)
            library.deleteLibraryCondition(activity.id)
        }
    }

    @Test fun mergedAchievementSetsAndMultiPauseSelectionsOnlyApplyOnProfileSave() {
        val id = UUID.randomUUID().toString()
        val first = ActivityUnlockCondition(description = "腹筋")
        val second = ActivityUnlockCondition(description = "掃除")
        val draft = ProfileEditPending(context, BlockedProfile(id = id, name = "Unified"))
        val sets = ActivityChallengeSetStore(context)
        val policy = ProfileUnlockPolicyStore(context)
        val activity = ActivityUnlockStore(context)
        val countSet = ActivityChallengeSet(
            conditionIds = listOf(first.id), pauseMinutes = 15,
            targets = mapOf(first.id to com.foqos.android.ui.activitylab.ActivityTarget(
                com.foqos.android.ui.activitylab.ActivityTargetKind.COUNT, 30)))
        val timedSet = ActivityChallengeSet(
            conditionIds = listOf(second.id), pauseMinutes = 10,
            targets = mapOf(second.id to com.foqos.android.ui.activitylab.ActivityTarget(
                com.foqos.android.ui.activitylab.ActivityTargetKind.MINUTES, 20)))
        try {
            draft.editLibrary(first)
            draft.editLibrary(second)
            draft.addActivity(first)
            draft.addActivity(second)
            draft.sets = listOf(countSet)
            draft.subSets = listOf(timedSet)
            draft.policy = draft.policy.copy(
                pauseMethods = setOf(UnlockMethod.NFC, UnlockMethod.ACTIVITY))
            assertThat(policy.configured(id)).isFalse()
            assertThat(sets.allSets(id)).isEmpty()
            assertThat(draft.ready()).isTrue()
            draft.commit()
            assertThat(policy.read(id).enabledPauseMethods())
                .containsExactly(UnlockMethod.NFC, UnlockMethod.ACTIVITY)
            assertThat(sets.sets(id)).containsExactly(countSet, timedSet).inOrder()
            assertThat(sets.subSets(id)).isEmpty()
            assertThat(activity.conditions(id)).hasSize(2)
        } finally {
            sets.deleteProfile(id)
            activity.deleteProfile(id)
            activity.deleteLibraryCondition(first.id)
            activity.deleteLibraryCondition(second.id)
            policy.delete(id)
        }
    }

    @Test fun oldSingleMainAndSubMovementMigrateToSetsOnlyOnCommit() {
        val id = UUID.randomUUID().toString()
        val main = ActivityUnlockCondition(description = "メイン腕立て")
        val sub = ActivityUnlockCondition(description = "サブ掃除")
        val activities = ActivityUnlockStore(context)
        val policies = ProfileUnlockPolicyStore(context)
        val store = ActivityChallengeSetStore(context)
        try {
            activities.save(id, listOf(main, sub))
            activities.setMainCondition(id, main.id)
            activities.setConditionPauseMinutes(id, sub.id, 15)
            policies.save(id, com.foqos.android.ui.activitylab.ProfileUnlockPolicy(
                pause = UnlockMethod.ACTIVITY, pauseMinutes = 20))
            val draft = ProfileEditPending(context, BlockedProfile(id = id, name = "Legacy"))
            assertThat(draft.sets.single().conditionIds).containsExactly(main.id)
            assertThat(draft.subSets.single().conditionIds).containsExactly(sub.id)
            assertThat(draft.subSets.single().pauseMinutes).isEqualTo(15)
            assertThat(store.hasMainSetConfig(id)).isFalse()
            assertThat(store.hasSubSetConfig(id)).isFalse()
            draft.commit()
            val migrated = store.sets(id)
            assertThat(migrated).hasSize(2)
            assertThat(store.subSets(id)).isEmpty()
            assertThat(migrated.map { it.conditionIds.single() })
                .containsExactly(main.id, sub.id).inOrder()
            assertThat(migrated.first().pauseMinutes).isEqualTo(20)
            assertThat(migrated.last().pauseMinutes).isEqualTo(15)
            assertThat(migrated.first().targets[main.id]).isNotNull()
            assertThat(migrated.last().targets[sub.id]).isNotNull()

            // Legacy sub sets must not reappear after they have been consolidated.
            val reopened = ProfileEditPending(context, BlockedProfile(id = id, name = "Legacy"))
            assertThat(reopened.sets).hasSize(2)
            assertThat(reopened.subSets).isEmpty()
            reopened.sets = reopened.sets.take(1)
            reopened.commit()
            val afterRemoval = ProfileEditPending(context, BlockedProfile(id = id, name = "Legacy"))
            assertThat(afterRemoval.sets).hasSize(1)
            assertThat(afterRemoval.subSets).isEmpty()
            assertThat(store.allSets(id)).hasSize(1)
        } finally {
            store.deleteProfile(id)
            activities.deleteProfile(id)
            policies.delete(id)
        }
    }

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
        pending.subSets = listOf(ActivityChallengeSet(conditionIds = listOf(condition.id), pauseMinutes = 12))
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
        assertThat(sets.subSets(id)).isEmpty()
        assertThat(location.read(id)).isNull()

        try {
            pending.commit()
            assertThat(policies.read(id).pause).isEqualTo(UnlockMethod.ACTIVITY)
            assertThat(policies.read(id).retryCooldownMinutes).isEqualTo(90)
            assertThat(sets.sets(id)).hasSize(2)
            assertThat(sets.subSets(id)).isEmpty()
            assertThat(sets.sets(id).map { it.pauseMinutes })
                .containsExactly(30, 12).inOrder()
            assertThat(sets.sets(id).all { it.targets.containsKey(condition.id) }).isTrue()
            assertThat(sets.sets(id).all {
                it.targets[condition.id]?.description() == "20回"
            }).isTrue()
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
