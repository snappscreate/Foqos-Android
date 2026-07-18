package com.foqos.android.data.model

import java.time.DayOfWeek
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Day of week, serialized as an int matching iOS `Weekday` raw values (1 = Sunday ... 7 =
 * Saturday) so schedule JSON payloads stay wire-compatible with the iOS export format.
 */
@Serializable(with = Weekday.Serializer::class)
enum class Weekday(val isoValue: Int) {
    SUNDAY(1),
    MONDAY(2),
    TUESDAY(3),
    WEDNESDAY(4),
    THURSDAY(5),
    FRIDAY(6),
    SATURDAY(7),
    ;

    /** The weekday immediately before this one, wrapping from Sunday to Saturday. */
    fun previous(): Weekday {
        val all = values()
        return all[(ordinal + all.size - 1) % all.size]
    }

    companion object {
        fun fromIsoValue(value: Int): Weekday = values().firstOrNull { it.isoValue == value } ?: SUNDAY

        /** Maps a [java.time.DayOfWeek] (Mon=1..Sun=7) to the equivalent [Weekday]. */
        fun fromJavaDayOfWeek(dayOfWeek: DayOfWeek): Weekday = when (dayOfWeek) {
            DayOfWeek.MONDAY -> MONDAY
            DayOfWeek.TUESDAY -> TUESDAY
            DayOfWeek.WEDNESDAY -> WEDNESDAY
            DayOfWeek.THURSDAY -> THURSDAY
            DayOfWeek.FRIDAY -> FRIDAY
            DayOfWeek.SATURDAY -> SATURDAY
            DayOfWeek.SUNDAY -> SUNDAY
        }
    }

    object Serializer : KSerializer<Weekday> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Weekday", PrimitiveKind.INT)

        override fun serialize(encoder: Encoder, value: Weekday) {
            encoder.encodeInt(value.isoValue)
        }

        override fun deserialize(decoder: Decoder): Weekday = fromIsoValue(decoder.decodeInt())
    }
}
