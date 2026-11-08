package com.foqos.android.ui.activitylab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** User-selected movement challenges. Kept separately from legacy strategy JSON for compatibility. */
data class ActivityUnlockCondition(
    val id: String = UUID.randomUUID().toString(),
    val description: String = "",
    val recordingSeconds: Int = 20,
    val countdownSeconds: Int = 5,
    val pauseMinutes: Int = 10,
    val frontCamera: Boolean = true,
) {
    fun normalized() = copy(
        description = description.trim().take(200),
        recordingSeconds = recordingSeconds.coerceIn(10, 60),
        countdownSeconds = countdownSeconds.coerceIn(0, 30),
        pauseMinutes = pauseMinutes.coerceIn(1, 180),
    )
}

data class SelectedActivityUnlock(val sessionId: String, val conditionId: String)

/** Selection is bound to an actual running session, never a global profile toggle. */
class ActivityUnlockStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("activity_unlock_conditions_v1", Context.MODE_PRIVATE)
    private val context = context.applicationContext

    fun conditions(profileId: String): List<ActivityUnlockCondition> = runCatching {
        val array = JSONArray(prefs.getString("conditions_$profileId", "[]"))
        (0 until array.length()).mapNotNull { i ->
            val json = array.optJSONObject(i) ?: return@mapNotNull null
            val id = json.optString("id")
            if (id.isBlank()) return@mapNotNull null
            ActivityUnlockCondition(
                id = id,
                description = json.optString("description"),
                recordingSeconds = json.optInt("recordingSeconds", 20),
                countdownSeconds = json.optInt("countdownSeconds", 5),
                pauseMinutes = json.optInt("pauseMinutes", 10),
                frontCamera = json.optBoolean("frontCamera", true),
            ).normalized()
        }.filter { it.description.isNotBlank() }
    }.getOrDefault(emptyList())

    /** A reusable catalog made from previously saved profile challenges. */
    fun reusableConditions(): List<ActivityUnlockCondition> =
        (sequenceOf("conditions___shared_catalog__") +
            prefs.all.keys.asSequence().filter { it.startsWith("conditions_") })
            .distinct()
            .flatMap { key -> conditions(key.removePrefix("conditions_")).asSequence() }
            .distinctBy { it.id }.toList()

    fun importCondition(profileId: String, condition: ActivityUnlockCondition) {
        val old = conditions(profileId)
        if (old.none { it.id == condition.id }) save(profileId, old + condition)
    }

    fun mainConditionId(profileId: String): String? =
        prefs.getString("main_$profileId", null)

    fun setMainCondition(profileId: String, id: String?) {
        require(id == null || conditions(profileId).any { it.id == id })
        prefs.edit().apply {
            if (id == null) remove("main_$profileId")
            else putString("main_$profileId", id)
        }.commit()
    }

    fun save(profileId: String, conditions: List<ActivityUnlockCondition>) {
        val array = JSONArray()
        conditions.forEach { condition ->
            val value = condition.normalized()
            array.put(JSONObject().put("id", value.id)
                .put("description", value.description)
                .put("recordingSeconds", value.recordingSeconds)
                .put("countdownSeconds", value.countdownSeconds)
                .put("pauseMinutes", value.pauseMinutes)
                .put("frontCamera", value.frontCamera))
        }
        if (conditions.none { it.id == mainConditionId(profileId) }) setMainCondition(profileId, null)
        check(prefs.edit().putString("conditions_$profileId", array.toString()).commit()) {
            "活動条件を保存できなかった"
        }
        if (profileId != "__shared_catalog__" && conditions.isNotEmpty()) {
            // Preserve registered movement definitions even after the source profile is deleted.
            val merged = (this.conditions("__shared_catalog__") + conditions)
                .distinctBy { it.id }
            save("__shared_catalog__", merged)
        }
    }

    private fun activeMain(profileId: String, sessionId: String): SelectedActivityUnlock? {
        if (ProfileUnlockPolicyStore(context).configured(profileId) &&
            ProfileUnlockPolicyStore(context).read(profileId).pause != UnlockMethod.ACTIVITY
        ) return null
        return mainConditionId(profileId)?.let { SelectedActivityUnlock(sessionId, it) }
    }

    fun hasExplicitSelection(profileId: String, sessionId: String): Boolean {
        val parts = prefs.getString("selected_$profileId", null)?.split("|", limit = 2)
        return parts != null && parts.size == 2 && parts[0] == sessionId
    }

    fun selection(profileId: String, sessionId: String): SelectedActivityUnlock? {
        val stored = prefs.getString("selected_$profileId", null)
            ?: return activeMain(profileId, sessionId)
        val parts = stored.split("|", limit = 2)
        if (parts.size != 2 || parts[0] != sessionId) {
            prefs.edit().remove("selected_$profileId").apply()
            return activeMain(profileId, sessionId)
        }
        return SelectedActivityUnlock(parts[0], parts[1])
    }

    /** Refuse to switch a selected condition back to NFC before success. */
    fun select(profileId: String, sessionId: String, conditionId: String): Boolean {
        if (conditions(profileId).none { it.id == conditionId }) return false
        // An implicit main challenge can be replaced once by a subcondition.
        // After that selection is explicit and cannot be changed until success.
        val stored = prefs.getString("selected_$profileId", null)
        val parts = stored?.split("|", limit = 2)
        if (parts?.size == 2 && parts[0] == sessionId) return parts[1] == conditionId
        return prefs.edit().putString("selected_$profileId", "$sessionId|$conditionId").commit()
    }

    fun savePauseMinutes(sessionId: String, minutes: Int) {
        prefs.edit().putInt("pause_$sessionId", minutes.coerceIn(1, 180)).commit()
    }

    fun pauseMinutes(sessionId: String): Int? =
        if (prefs.contains("pause_$sessionId")) prefs.getInt("pause_$sessionId", 10) else null

    fun clearPauseMinutes(sessionId: String) {
        prefs.edit().remove("pause_$sessionId").apply()
    }

    fun deleteProfile(profileId: String) {
        prefs.edit().remove("conditions_$profileId").remove("main_$profileId")
            .remove("selected_$profileId").commit()
    }

    fun clear(profileId: String) {
        prefs.edit().remove("selected_$profileId").apply()
    }
}
