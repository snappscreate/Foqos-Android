package com.foqos.android.service.nfc

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NfcTagWriterTest {

    // --- NfcWriteDecision: pure, hardware-free - plain JVM assertions, no Robolectric needed. ---

    @Test
    fun `rejects a locked tag regardless of capacity`() {
        val result = NfcWriteDecision.evaluate(isWritable = false, capacityBytes = 1000, neededBytes = 10)
        assertThat(result).isEqualTo(WriteResult.Error("This tag is locked and cannot be written"))
    }

    @Test
    fun `rejects a tag that is too small for the message`() {
        val result = NfcWriteDecision.evaluate(isWritable = true, capacityBytes = 20, neededBytes = 64)
        assertThat(result).isEqualTo(WriteResult.Error("This tag is too small for a profile link (needs 64 bytes, has 20)"))
    }

    @Test
    fun `allows a writable tag with enough capacity`() {
        val result = NfcWriteDecision.evaluate(isWritable = true, capacityBytes = 144, neededBytes = 64)
        assertThat(result).isNull()
    }

    @Test
    fun `capacity check is inclusive of exact fit`() {
        val result = NfcWriteDecision.evaluate(isWritable = true, capacityBytes = 64, neededBytes = 64)
        assertThat(result).isNull()
    }

    @Test
    fun `locked check wins over an undersized capacity`() {
        val result = NfcWriteDecision.evaluate(isWritable = false, capacityBytes = 4, neededBytes = 64)
        assertThat(result).isEqualTo(WriteResult.Error("This tag is locked and cannot be written"))
    }
}
