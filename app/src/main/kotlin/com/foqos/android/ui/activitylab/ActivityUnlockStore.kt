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
            ).normalized()
        }.filter { it.description.isNotBlank() }
    }.getOrDefault(emptyList())

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
                .put("pauseMinutes", value.pauseMinutes))
        }
        if (conditions.none { it.id == mainConditionId(profileId) }) setMainCondition(profileId, null)
        check(prefs.edit().putString("conditions_$profileId", array.toString()).commit()) {
            "活動条件を保存できなかった"
        }
    }

    fun selection(profileId: String, sessionId: String): SelectedActivityUnlock? {
        val stored = prefs.getString("selected_$profileId", null)
            ?: return mainConditionId(profileId)?.let { SelectedActivityUnlock(sessionId, it) }
        val parts = stored.split("|", limit = 2)
        if (parts.size != 2 || parts[0] != sessionId) return null
        return SelectedActivityUnlock(parts[0], parts[1])
    }

    /** Refuse to switch a selected condition back to NFC before success. */
    fun select(profileId: String, sessionId: String, conditionId: String): Boolean {
        if (conditions(profileId).none { it.id == conditionId }) return false
        val existing = selection(profileId, sessionId)
        if (existing != null) return existing.conditionId == conditionId
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

    fun clear(profileId: String) {
        prefs.edit().remove("selected_$profileId").apply()
    }
}
