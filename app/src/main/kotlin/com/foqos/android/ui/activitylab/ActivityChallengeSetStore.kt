package com.foqos.android.ui.activitylab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** OR across sets; AND among distinct movement IDs inside each set. */
data class ActivityChallengeSet(
    val id: String = UUID.randomUUID().toString(),
    val conditionIds: List<String>,
    val pauseMinutes: Int = 10,
    val targets: Map<String, ActivityTarget> = emptyMap(),
)

enum class ActivityTargetKind { COUNT, MINUTES }

data class ActivityTarget(
    val kind: ActivityTargetKind = ActivityTargetKind.COUNT,
    val amount: Int = 30,
) {
    fun description(): String = if (kind == ActivityTargetKind.COUNT) "${amount}回" else "${amount}分"
}

data class ActivityChallengeProgress(
    val sessionId: String,
    val setId: String,
    val completedIds: Set<String>,
)

enum class ActivityProofResult { REJECTED, STEP_COMPLETED, PAUSED }

/** Profiles own their reward sets; movements remain reusable library items. */
class ActivityChallengeSetStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "activity_challenge_sets_v1", Context.MODE_PRIVATE)

    fun sets(profileId: String): List<ActivityChallengeSet> = runCatching {
        val json = JSONArray(prefs.getString("sets_$profileId", "[]"))
        (0 until json.length()).mapNotNull { i ->
            val entry = json.optJSONObject(i) ?: return@mapNotNull null
            val values = entry.optJSONArray("conditions") ?: JSONArray()
            val ids = (0 until values.length()).mapNotNull { n ->
                values.optString(n).takeIf { it.isNotBlank() }
            }.distinct()
            if (ids.isEmpty()) return@mapNotNull null
            ActivityChallengeSet(
                id = entry.optString("id").takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null,
                conditionIds = ids,
                pauseMinutes = entry.optInt("minutes", 10).coerceIn(1, 180),
                targets = readTargets(entry),
                targets = readTargets(entry),
            )
        }
    }.getOrDefault(emptyList())

    /** Additional alternative AI pause sets, independent from the main pause method. */
    fun hasMainSetConfig(profileId: String): Boolean = prefs.contains("sets_$profileId")
    fun hasSubSetConfig(profileId: String): Boolean = prefs.contains("sub_sets_$profileId")

    fun subSets(profileId: String): List<ActivityChallengeSet> = readGroups("sub_sets_$profileId")

    fun allSets(profileId: String): List<ActivityChallengeSet> = sets(profileId) + subSets(profileId)

    fun saveSubSets(profileId: String, value: List<ActivityChallengeSet>) =
        writeGroups("sub_sets_$profileId", profileId, value)

    private fun readTargets(entry: JSONObject): Map<String, ActivityTarget> {
        val json = entry.optJSONObject("targets") ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { id ->
            val data = json.optJSONObject(id) ?: return@mapNotNull null
            val kind = runCatching { ActivityTargetKind.valueOf(data.optString("kind")) }
                .getOrDefault(ActivityTargetKind.COUNT)
            id to ActivityTarget(kind, data.optInt("amount", 30).coerceIn(1, 720))
        }.toMap()
    }

    private fun writeTargets(set: ActivityChallengeSet): JSONObject = JSONObject().apply {
        set.targets.forEach { (id, target) ->
            put(id, JSONObject().put("kind", target.kind.name).put("amount", target.amount))
        }
    }

    private fun readGroups(key: String): List<ActivityChallengeSet> = runCatching {
        val json = JSONArray(prefs.getString(key, "[]"))
        (0 until json.length()).mapNotNull { i ->
            val entry = json.optJSONObject(i) ?: return@mapNotNull null
            val values = entry.optJSONArray("conditions") ?: JSONArray()
            val ids = (0 until values.length()).mapNotNull { n ->
                values.optString(n).takeIf(String::isNotBlank)
            }.distinct()
            if (ids.isEmpty()) return@mapNotNull null
            ActivityChallengeSet(
                id = entry.optString("id").takeIf(String::isNotBlank)
                    ?: return@mapNotNull null,
                conditionIds = ids,
                pauseMinutes = entry.optInt("minutes", 10).coerceIn(1, 180),
            )
        }
    }.getOrDefault(emptyList())

    private fun writeGroups(key: String, profileId: String, value: List<ActivityChallengeSet>) {
        require(value.all { it.conditionIds.isNotEmpty() &&
            it.conditionIds.size == it.conditionIds.distinct().size &&
            it.pauseMinutes in 1..180 &&
            it.targets.keys.all(it.conditionIds::contains) &&
            it.targets.values.all { target -> target.amount in 1..720 } })
        val encoded = JSONArray().apply {
            value.forEach { set ->
                put(JSONObject().put("id", set.id).put("minutes", set.pauseMinutes)
                    .put("conditions", JSONArray(set.conditionIds))
                    .put("targets", writeTargets(set)))
            }
        }
        check(prefs.edit().putString(key, encoded.toString())
            .remove("progress_$profileId").commit()) { "動作セットを保存できなかった" }
    }

    fun save(profileId: String, value: List<ActivityChallengeSet>) =
        writeGroups("sets_$profileId", profileId, value)

    fun progress(profileId: String, sessionId: String): ActivityChallengeProgress? = runCatching {
        val data = JSONObject(prefs.getString("progress_$profileId", "{}") ?: "{}")
        if (data.optString("session") != sessionId) return@runCatching null
        val setId = data.optString("set")
        if (allSets(profileId).none { it.id == setId }) return@runCatching null
        val values = data.optJSONArray("completed") ?: JSONArray()
        ActivityChallengeProgress(sessionId, setId,
            (0 until values.length()).map { values.optString(it) }.toSet())
    }.getOrNull()

    fun chooseSet(profileId: String, sessionId: String, setId: String): Boolean {
        val group = allSets(profileId).firstOrNull { it.id == setId } ?: return false
        // Rechoosing the same set does not erase previous successful steps.
        val existing = progress(profileId, sessionId)
        if (existing?.setId == setId) return true
        val data = JSONObject().put("session", sessionId).put("set", group.id)
            .put("completed", JSONArray())
        return prefs.edit().putString("progress_$profileId", data.toString()).commit()
    }

    fun nextConditions(profileId: String, sessionId: String): List<String> {
        val state = progress(profileId, sessionId) ?: return emptyList()
        val group = allSets(profileId).firstOrNull { it.id == state.setId }
            ?: return emptyList()
        return group.conditionIds.filterNot { it in state.completedIds }
    }

    /** Called only after the active session and real Gemini verdict are authenticated. */
    fun complete(profileId: String, sessionId: String, conditionId: String): ActivityProofResult {
        val state = progress(profileId, sessionId) ?: return ActivityProofResult.REJECTED
        val group = allSets(profileId).firstOrNull { it.id == state.setId }
            ?: return ActivityProofResult.REJECTED
        if (conditionId !in group.conditionIds || conditionId in state.completedIds)
            return ActivityProofResult.REJECTED
        val finished = state.completedIds + conditionId
        val json = JSONObject().put("session", sessionId).put("set", state.setId)
            .put("completed", JSONArray(finished.toList()))
        if (!prefs.edit().putString("progress_$profileId", json.toString()).commit())
            return ActivityProofResult.REJECTED
        return if (group.conditionIds.all { it in finished }) ActivityProofResult.PAUSED
            else ActivityProofResult.STEP_COMPLETED
    }

    fun clearProgress(profileId: String) {
        prefs.edit().remove("progress_$profileId").apply()
    }

    fun deleteProfile(profileId: String) {
        prefs.edit().remove("sets_$profileId").remove("sub_sets_$profileId")
            .remove("progress_$profileId").commit()
    }
}
