package com.foqos.android.data.model

import com.foqos.android.data.db.entity.BlockedProfileEntity
import com.foqos.android.data.db.entity.BlockedProfileSessionEntity
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Domain-facing representation of a blocking profile (parsed form of [BlockedProfileEntity]):
 * JSON columns are decoded into their real types and timestamps are [Instant]s.
 */
data class BlockedProfile(
    val id: String,
    val name: String,
    val blockedPackages: List<String> = emptyList(),
    val allowModeEnabled: Boolean = false,
    val domains: List<String> = emptyList(),
    val allowModeDomains: Boolean = false,
    val strategyId: String? = null,
    val strategyDataJson: String? = null,
    val sortOrder: Int = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val reminderTimeInSeconds: Long? = null,
    val customReminderMessage: String? = null,
    val enableBreaks: Boolean = false,
    val breakTimeInMinutes: Int = 15,
    val allowMultipleBreaks: Boolean = false,
    val enableStrictMode: Boolean = false,
    val enableBlockAppInstallation: Boolean = false,
    val physicalUnblockItems: List<PhysicalUnblockItem> = emptyList(),
    val schedule: BlockedProfileSchedule? = null,
    val disableBackgroundStops: Boolean = false,
    val enableEmergencyUnblock: Boolean = true,
) {

    /**
     * Whether [code] of [type] can unblock this profile: if there are configured physical
     * unblock items of [type], [code] must (after normalization) match one of them. If no items
     * of [type] are configured, this returns false - the calling strategy applies its own default
     * unblock rules in that case (e.g. "any tag/code stops the session").
     */
    fun canUnblock(code: String, type: PhysicalUnblockType?): Boolean {
        if (type == null) return false
        val itemsForType = physicalUnblockItems.filter { it.type == type }
        if (itemsForType.isEmpty()) return false

        val normalizedCode = PhysicalUnblockItem.normalizeCodeValue(code)
        return itemsForType.any { PhysicalUnblockItem.normalizeCodeValue(it.codeValue) == normalizedCode }
    }

    fun hasPhysicalUnblockItem(type: PhysicalUnblockType): Boolean =
        physicalUnblockItems.any { it.type == type }

    /**
     * Whether this profile allows timed breaks. The actual "does this strategy support breaks"
     * rule lives in the strategy layer (soft-unblock strategies disallow timed breaks); this just
     * forwards to the supplied [strategyAllowsTimedBreaks] predicate with this profile's
     * configured [strategyId].
     */
    fun allowsTimedBreaks(strategyAllowsTimedBreaks: (String?) -> Boolean): Boolean =
        strategyAllowsTimedBreaks(strategyId)
}

/**
 * Domain-facing representation of a blocking session (parsed form of
 * [BlockedProfileSessionEntity]): timestamps are [Instant]s.
 */
data class BlockedProfileSession(
    val id: String,
    val profileId: String,
    val tag: String,
    val startTime: Instant,
    val endTime: Instant? = null,
    val breakStartTime: Instant? = null,
    val breakEndTime: Instant? = null,
    val usedBreakDurationInSeconds: Long = 0,
    val pauseStartTime: Instant? = null,
    val pauseEndTime: Instant? = null,
    val forceStarted: Boolean = false,
) {

    val isActive: Boolean
        get() = endTime == null

    fun isBreakActive(now: Instant = Instant.now()): Boolean =
        breakStartTime != null && breakEndTime == null

    fun isPauseActive(now: Instant = Instant.now()): Boolean =
        pauseStartTime != null && pauseEndTime == null

    fun duration(now: Instant = Instant.now()): Duration {
        val end = endTime ?: now
        return Duration.between(startTime, end)
    }
}

// --- Entity <-> domain mappers -------------------------------------------------------------

private fun decodeStringList(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        modelJson.decodeFromString(json)
    } catch (e: Exception) {
        emptyList()
    }
}

private fun encodeStringListOrNull(list: List<String>): String? =
    if (list.isEmpty()) null else modelJson.encodeToString(list)

private fun decodePhysicalUnblockItems(json: String?): List<PhysicalUnblockItem> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        modelJson.decodeFromString(json)
    } catch (e: Exception) {
        emptyList()
    }
}

private fun encodePhysicalUnblockItemsOrNull(items: List<PhysicalUnblockItem>): String? =
    if (items.isEmpty()) null else modelJson.encodeToString(items)

private fun decodeSchedule(json: String?): BlockedProfileSchedule? {
    if (json.isNullOrBlank()) return null
    return try {
        modelJson.decodeFromString(json)
    } catch (e: Exception) {
        null
    }
}

fun BlockedProfileEntity.toDomain(): BlockedProfile = BlockedProfile(
    id = id,
    name = name,
    blockedPackages = decodeStringList(blockedPackagesJson),
    allowModeEnabled = allowModeEnabled,
    domains = decodeStringList(domainsJson),
    allowModeDomains = allowModeDomains,
    strategyId = strategyId,
    strategyDataJson = strategyDataJson,
    sortOrder = sortOrder,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
    reminderTimeInSeconds = reminderTimeInSeconds,
    customReminderMessage = customReminderMessage,
    enableBreaks = enableBreaks,
    breakTimeInMinutes = breakTimeInMinutes,
    allowMultipleBreaks = allowMultipleBreaks,
    enableStrictMode = enableStrictMode,
    enableBlockAppInstallation = enableBlockAppInstallation,
    physicalUnblockItems = decodePhysicalUnblockItems(physicalUnblockItemsJson),
    schedule = decodeSchedule(scheduleJson),
    disableBackgroundStops = disableBackgroundStops,
    enableEmergencyUnblock = enableEmergencyUnblock,
)

fun BlockedProfile.toEntity(): BlockedProfileEntity = BlockedProfileEntity(
    id = id,
    name = name,
    blockedPackagesJson = modelJson.encodeToString(blockedPackages),
    allowModeEnabled = allowModeEnabled,
    domainsJson = encodeStringListOrNull(domains),
    allowModeDomains = allowModeDomains,
    strategyId = strategyId,
    strategyDataJson = strategyDataJson,
    sortOrder = sortOrder,
    createdAt = createdAt.toEpochMilli(),
    updatedAt = updatedAt.toEpochMilli(),
    reminderTimeInSeconds = reminderTimeInSeconds,
    customReminderMessage = customReminderMessage,
    enableBreaks = enableBreaks,
    breakTimeInMinutes = breakTimeInMinutes,
    allowMultipleBreaks = allowMultipleBreaks,
    enableStrictMode = enableStrictMode,
    enableBlockAppInstallation = enableBlockAppInstallation,
    physicalUnblockItemsJson = encodePhysicalUnblockItemsOrNull(physicalUnblockItems),
    scheduleJson = schedule?.let { modelJson.encodeToString(it) },
    disableBackgroundStops = disableBackgroundStops,
    enableEmergencyUnblock = enableEmergencyUnblock,
)

fun BlockedProfileSessionEntity.toDomain(): BlockedProfileSession = BlockedProfileSession(
    id = id,
    profileId = profileId,
    tag = tag,
    startTime = Instant.ofEpochMilli(startTime),
    endTime = endTime?.let { Instant.ofEpochMilli(it) },
    breakStartTime = breakStartTime?.let { Instant.ofEpochMilli(it) },
    breakEndTime = breakEndTime?.let { Instant.ofEpochMilli(it) },
    usedBreakDurationInSeconds = usedBreakDurationInSeconds,
    pauseStartTime = pauseStartTime?.let { Instant.ofEpochMilli(it) },
    pauseEndTime = pauseEndTime?.let { Instant.ofEpochMilli(it) },
    forceStarted = forceStarted,
)

fun BlockedProfileSession.toEntity(): BlockedProfileSessionEntity = BlockedProfileSessionEntity(
    id = id,
    profileId = profileId,
    tag = tag,
    startTime = startTime.toEpochMilli(),
    endTime = endTime?.toEpochMilli(),
    breakStartTime = breakStartTime?.toEpochMilli(),
    breakEndTime = breakEndTime?.toEpochMilli(),
    usedBreakDurationInSeconds = usedBreakDurationInSeconds,
    pauseStartTime = pauseStartTime?.toEpochMilli(),
    pauseEndTime = pauseEndTime?.toEpochMilli(),
    forceStarted = forceStarted,
)
