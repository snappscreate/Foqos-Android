package com.foqos.android.service.nfc

import android.nfc.NdefRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [NfcTagWriter.buildProfileNdefMessage] needs real `android.nfc.NdefRecord`/`NdefMessage` - Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NfcTagWriterMessageTest {

    @Test
    fun `writes a uri record followed by an android application record`() {
        val message = NfcTagWriter.buildProfileNdefMessage("550e8400-e29b-41d4-a716-446655440000")

        assertThat(message.records).hasLength(2)
        assertThat(message.records[0].toUri().toString())
            .isEqualTo("foqos://profile/550e8400-e29b-41d4-a716-446655440000")
        assertThat(message.records[1].tnf).isEqualTo(NdefRecord.TNF_EXTERNAL_TYPE)
        assertThat(String(message.records[1].payload)).isEqualTo(FOQOS_PACKAGE_NAME)
    }
}
