package com.foqos.android.ui.profile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.foqos.android.data.model.BlockedProfile
import com.foqos.android.service.location.LocationAutomationStore
import com.foqos.android.service.location.LocationRule
import com.foqos.android.ui.activitylab.ActivityChallengeSet
import com.foqos.android.ui.activitylab.ActivityChallengeSetStore
import com.foqos.android.ui.activitylab.ActivityUnlockCondition
import com.foqos.android.ui.activitylab.ActivityUnlockStore
import com.foqos.android.ui.activitylab.ProfileUnlockPolicy
import com.foqos.android.ui.activitylab.ProfileUnlockPolicyStore

/**
 * Edit-screen transaction buffer. UI mutations MUST stay here until ProfileEditViewModel.save().
 * SharedPreferences and geofences are not altered by changing any of these fields.
 */
class ProfileEditPending(context: Context, profile: BlockedProfile) {
    val profileId = profile.id
    private val policyStore = ProfileUnlockPolicyStore(context)
    private val activityStore = ActivityUnlockStore(context)
    private val setStore = ActivityChallengeSetStore(context)
    private val locationStore = LocationAutomationStore(context)

    var policy by mutableStateOf(policyStore.forProfile(profile))
    var mainConditionId by mutableStateOf(activityStore.mainConditionId(profile.id))
    var activities by mutableStateOf(activityStore.conditions(profile.id))
    var sets by mutableStateOf(setStore.sets(profile.id))
    var locationRule: LocationRule? by mutableStateOf(locationStore.read(profile.id))
    var locationEnabled by mutableStateOf(locationStore.enabled(profile.id))
    var cooldownMinutes by mutableStateOf(policy.retryCooldownMinutes)
    var linkedPhysicalItems by mutableStateOf(profile.physicalUnblockItems)
    var activityRewards by mutableStateOf(
        activities.associate { it.id to activityStore.conditionPauseMinutes(profile.id, it.id, policy.pauseMinutes) }
    )

    fun addActivity(activity: ActivityUnlockCondition) {
        if (activities.none { it.id == activity.id }) activities = activities + activity
    }

    fun removeActivity(id: String): Boolean {
        if (sets.any { id in it.conditionIds } || mainConditionId == id) return false
        activities = activities.filterNot { it.id == id }
        activityRewards = activityRewards - id
        return true
    }

    fun ready(): Boolean {
        val ids = activities.map { it.id }.toSet()
        return sets.all { it.conditionIds.isNotEmpty() && it.conditionIds.all(ids::contains) } &&
            (policy.pause != com.foqos.android.ui.activitylab.UnlockMethod.ACTIVITY ||
                (sets.isNotEmpty() || mainConditionId in ids))
    }

    /** Persist only after profile has passed validation and its Save button was pressed. */
    fun commit() {
        val merged = policy.copy(retryCooldownMinutes = cooldownMinutes)
        activityStore.save(profileId, activities)
        activityStore.setMainCondition(profileId, mainConditionId?.takeIf { id ->
            activities.any { it.id == id }
        })
        activityRewards.forEach { (id, duration) ->
            if (activities.any { it.id == id }) activityStore.setConditionPauseMinutes(profileId, id, duration)
        }
        setStore.save(profileId, sets)
        policyStore.save(profileId, merged)
    }
}
