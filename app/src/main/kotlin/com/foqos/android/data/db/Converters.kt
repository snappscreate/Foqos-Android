package com.foqos.android.data.db

/**
 * Room [androidx.room.TypeConverter] holder for [AppDatabase].
 *
 * Intentionally empty: every entity field is either a Room-native type (String, Long, Int,
 * Boolean and their nullable variants) or a JSON TEXT column. Lists and nested objects (blocked
 * packages, domains, physical unblock items, schedule, strategy data) are (de)serialized
 * explicitly in `com.foqos.android.data.model` mappers/companion functions rather than through
 * Room TypeConverters, keeping entities as plain data holders and JSON handling co-located with
 * the domain models that own it. This class exists as an explicit extension point should a future
 * entity field need a converter.
 */
class Converters
