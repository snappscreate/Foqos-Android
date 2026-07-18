package com.foqos.android.service.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [NfcTagContentParser.findFoqosUri] parses real [NdefRecord]s (needs the real AOSP NDEF parsing
 * logic for `NdefRecord.toUri()`, so this runs under Robolectric rather than a plain unit test).
 * Each case round-trips through actual serialized NDEF bytes ([NdefMessage.toByteArray] ->
 * `NdefMessage(ByteArray)`) rather than hand-built record lists, so it's exercising the same byte
 * parsing a scanned tag would go through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NfcTagContentParserTest {

    private val profileId = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `finds a foqos scheme uri record among raw NDEF bytes`() {
        val message = NdefMessage(
            arrayOf(
                NdefRecord.createUri(ProfileLinkCodec.buildProfileUri(profileId)),
                NdefRecord.createApplicationRecord(FOQOS_PACKAGE_NAME),
            ),
        )
        val roundTripped = NdefMessage(message.toByteArray())

        assertThat(NfcTagContentParser.findFoqosUri(roundTripped.records.toList()))
            .isEqualTo(ProfileLinkCodec.buildProfileUri(profileId))
    }

    @Test
    fun `finds the https universal-link uri record written by iOS`() {
        val message = NdefMessage(arrayOf(NdefRecord.createUri("https://foqos.app/profile/$profileId")))
        val roundTripped = NdefMessage(message.toByteArray())

        assertThat(NfcTagContentParser.findFoqosUri(roundTripped.records.toList()))
            .isEqualTo("https://foqos.app/profile/$profileId")
    }

    @Test
    fun `returns null when no record is a recognized profile link`() {
        val message = NdefMessage(arrayOf(NdefRecord.createUri("https://example.com/not-foqos")))
        val roundTripped = NdefMessage(message.toByteArray())

        assertThat(NfcTagContentParser.findFoqosUri(roundTripped.records.toList())).isNull()
    }

    @Test
    fun `returns null for a text record with no uri at all`() {
        val message = NdefMessage(arrayOf(NdefRecord.createTextRecord("en", "just some text")))
        val roundTripped = NdefMessage(message.toByteArray())

        assertThat(NfcTagContentParser.findFoqosUri(roundTripped.records.toList())).isNull()
    }

    @Test
    fun `hex-encodes a tag id as lowercase with no separators`() {
        val bytes = byteArrayOf(0x04, 0xA2.toByte(), 0xFF.toByte(), 0x19)
        assertThat(NfcTagContentParser.hexEncodeTagId(bytes)).isEqualTo("04a2ff19")
    }

    @Test
    fun `hex-encodes an empty id as an empty string`() {
        assertThat(NfcTagContentParser.hexEncodeTagId(byteArrayOf())).isEqualTo("")
    }
}
