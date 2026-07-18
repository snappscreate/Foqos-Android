package com.foqos.android.data.model

import kotlinx.serialization.json.Json

/**
 * Shared lenient JSON codec used to persist model payloads (schedules, strategy data, physical
 * unblock items, string lists, ...) as TEXT columns in Room entities.
 */
internal val modelJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}
