package com.foqos.android.service.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of [NfcTagWriter.writeProfileTag]. */
sealed interface WriteResult {
    data object Success : WriteResult
    data class Error(val message: String) : WriteResult
}

/**
 * The Android package id written into every profile tag's Android Application Record, so tapping
 * the tag opens/foregrounds Foqos even if another NFC-capable app is also registered for the URI.
 */
const val FOQOS_PACKAGE_NAME = "com.foqos.android"

/**
 * Pure, hardware-free decision logic for [NfcTagWriter.writeToNdef] - factored out so the
 * locked/capacity rejection rules are unit-testable without a real [Ndef] tag connection. Mirrors
 * iOS `NFCWriter`'s `.readOnly` / `messageSize > capacity` checks. Returns the [WriteResult.Error]
 * to fail with, or null if the write may proceed.
 */
internal object NfcWriteDecision {
    fun evaluate(isWritable: Boolean, capacityBytes: Int, neededBytes: Int): WriteResult.Error? = when {
        !isWritable -> WriteResult.Error("This tag is locked and cannot be written")
        neededBytes > capacityBytes ->
            WriteResult.Error("This tag is too small for a profile link (needs $neededBytes bytes, has $capacityBytes)")
        else -> null
    }
}

/**
 * Writes the `foqos://profile/{uuid}` NDEF URI record (+ an Android Application Record for
 * [FOQOS_PACKAGE_NAME]) to a scanned NFC tag - Android counterpart of iOS `NFCWriter.writeURL`.
 * Handles the three tag shapes iOS distinguishes: already NDEF-formatted + writable, NDEF-capable
 * but blank ([NdefFormatable] - format then write), and locked/read-only or undersized (rejected
 * with a message mirroring iOS's tone).
 */
object NfcTagWriter {

    /** Builds the two-record NDEF message written to every profile tag: profile URI + AAR. */
    fun buildProfileNdefMessage(profileId: String): NdefMessage {
        val uriRecord = NdefRecord.createUri(ProfileLinkCodec.buildProfileUri(profileId))
        val applicationRecord = NdefRecord.createApplicationRecord(FOQOS_PACKAGE_NAME)
        return NdefMessage(arrayOf(uriRecord, applicationRecord))
    }

    /** Writes [profileId]'s profile link to [tag]. Must be called with a freshly-discovered [tag] (not yet connected). */
    suspend fun writeProfileTag(tag: Tag, profileId: String): WriteResult = withContext(Dispatchers.IO) {
        val message = buildProfileNdefMessage(profileId)
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) return@withContext writeToNdef(ndef, message)

            val formatable = NdefFormatable.get(tag)
            if (formatable != null) return@withContext writeToFormatable(formatable, message)

            WriteResult.Error("This tag doesn't support NDEF and can't be written to - use a blank NFC tag instead")
        } catch (e: IOException) {
            WriteResult.Error("Connection lost — hold the tag still and try again")
        } catch (e: FormatException) {
            WriteResult.Error("Failed to write - this tag's format isn't supported")
        } catch (e: SecurityException) {
            WriteResult.Error("Failed to write - this tag rejected the connection")
        }
    }

    private fun writeToNdef(ndef: Ndef, message: NdefMessage): WriteResult {
        ndef.connect()
        try {
            val rejection = NfcWriteDecision.evaluate(
                isWritable = ndef.isWritable,
                capacityBytes = ndef.maxSize,
                neededBytes = message.toByteArray().size,
            )
            if (rejection != null) return rejection

            ndef.writeNdefMessage(message)
            return WriteResult.Success
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun writeToFormatable(formatable: NdefFormatable, message: NdefMessage): WriteResult {
        formatable.connect()
        try {
            formatable.format(message)
            return WriteResult.Success
        } finally {
            runCatching { formatable.close() }
        }
    }
}
