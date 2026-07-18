package com.foqos.android.service.nfc

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter

/** Device NFC capability/state checks, used to give the UI a clear hint instead of a silent no-op. */
object NfcStatus {

    /** Whether this device has NFC hardware at all. */
    fun isNfcSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)

    /** Whether NFC hardware is present AND currently turned on in system settings. */
    fun isNfcEnabled(context: Context): Boolean =
        NfcAdapter.getDefaultAdapter(context)?.isEnabled == true
}
