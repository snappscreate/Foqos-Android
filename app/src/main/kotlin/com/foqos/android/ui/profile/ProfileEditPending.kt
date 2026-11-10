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
    private val startingLibrary = activityStore.reusableConditions()
    var library by mutableStateOf(startingLibrary)
    var removedLibraryIds by mutableStateOf(emptySet<String>())
    var sets by mutableStateOf(setStore.sets(profile.id))
    var locationRule: LocationRule? by mutableStateOf(locationStore.read(profile.id))
    var locationEnabled by mutableStateOf(locationStore.enabled(profile.id))
    var cooldownMinutes by mutableStateOf(policy.retryCooldownMinutes)
    var linkedPhysicalItems by mutableStateOf(profile.physicalUnblockItems)
    var activityRewards by mutableStateOf(
        activities.associate { it.id to activityStore.conditionPauseMinutes(profile.id, it.id, policy.pauseMinutes) }
    )

    fun editLibrary(item: ActivityUnlockCondition) {
        library = library.filterNot { it.id == item.id } + item.normalized()
    }

    fun removeFromLibrary(id: String) {
        removedLibraryIds = removedLibraryIds + id
        library = library.filterNot { it.id == id }
    }

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
                (sets.isNotEmpty() || mainConditionId?.let(ids::contains) == true))
    }

    /** Persist only after profile has passed validation and its Save button was pressed. */
    fun commit() {
        val merged = policy.copy(retryCooldownMinutes = cooldownMinutes)
        val prior = startingLibrary.associateBy { it.id }
        // Save this profile's new associations first, then validate global deletions.
        activityStore.save(profileId, activities.map { item ->
            library.firstOrNull { it.id == item.id } ?: item
        })
        library.filter { prior[it.id] != it }.forEach(activityStore::updateLibraryCondition)
        removedLibraryIds.forEach { id ->
            check(activityStore.deleteLibraryCondition(id)) {
                "この動作は他のプロファイルで使用されています。削除できないよ"
            }
        }
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
