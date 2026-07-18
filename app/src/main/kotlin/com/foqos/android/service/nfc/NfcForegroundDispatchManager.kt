package com.foqos.android.service.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Parcelable
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Owns NFC foreground-dispatch registration on whichever [Activity] is currently in front, and
 * routes scanned tags either to a UI that is actively waiting for one (strategy start/stop,
 * physical-unlock registration) or - if nothing is waiting - to [handleIntent]'s
 * `onBackgroundProfileScan` callback, mirroring iOS's "just tap a tag while the app happens to be
 * open" auto-toggle behavior. Cold-launch (`ACTION_NDEF_DISCOVERED` starting the app fresh) is
 * handled separately by `MainActivity.onCreate` reading its own launch [Intent] through the same
 * [handleIntent] entry point.
 */
@Singleton
class NfcForegroundDispatchManager @Inject constructor() {

    private val _tagScans = MutableSharedFlow<NfcTagContent>(extraBufferCapacity = 1)

    /** Tags scanned while [setWaitingForScan] is true - collected by whichever sheet requested a scan. */
    val tagScans: SharedFlow<NfcTagContent> = _tagScans.asSharedFlow()

    private val _rawTagScans = MutableSharedFlow<Tag>(extraBufferCapacity = 1)

    /**
     * The raw [Tag] behind every scan emitted on [tagScans] while [setWaitingForScan] is true -
     * needed (unlike the parsed [NfcTagContent]) by [com.foqos.android.service.nfc.NfcTagWriter],
     * which writes directly to a freshly-discovered [Tag] connection.
     */
    val rawTagScans: SharedFlow<Tag> = _rawTagScans.asSharedFlow()

    private val waitingForScan = AtomicBoolean(false)

    /**
     * Marks whether some UI is actively waiting for the next scanned tag (a `ScanNfc` strategy
     * sheet, or the physical-unlock "Add NFC tag" flow). While true, [handleIntent] routes scans
     * to [tagScans] instead of treating them as a background profile toggle.
     */
    fun setWaitingForScan(waiting: Boolean) {
        waitingForScan.set(waiting)
    }

    /** Registers foreground dispatch so NFC intents are delivered to [activity] via `onNewIntent` while it's resumed. */
    fun enable(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return

        val intent = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pendingIntent = PendingIntent.getActivity(activity, 0, intent, pendingIntentFlags)

        val filters = arrayOf(
            IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
        )
        val techLists = arrayOf(
            arrayOf(Ndef::class.java.name),
            arrayOf(NdefFormatable::class.java.name),
            arrayOf(NfcA::class.java.name),
            arrayOf(NfcB::class.java.name),
            arrayOf(NfcF::class.java.name),
            arrayOf(NfcV::class.java.name),
            arrayOf(IsoDep::class.java.name),
            arrayOf(MifareClassic::class.java.name),
            arrayOf(MifareUltralight::class.java.name),
        )

        adapter.enableForegroundDispatch(activity, pendingIntent, filters, techLists)
    }

    /** Unregisters foreground dispatch - call from `onPause`. */
    fun disable(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        adapter.disableForegroundDispatch(activity)
    }

    /**
     * Extracts a scanned tag from [intent] (an NFC intent-filter launch, or one forwarded through
     * foreground dispatch) and routes it: to [tagScans] if [setWaitingForScan] is currently true,
     * otherwise - only for a recognized foqos profile URI - to [onBackgroundProfileScan] with the
     * parsed profile id. A no-op if [intent] carries no `EXTRA_TAG`.
     */
    fun handleIntent(intent: Intent, onBackgroundProfileScan: (String) -> Unit) {
        val tag = intent.parcelableExtraCompat<Tag>(NfcAdapter.EXTRA_TAG) ?: return
        val message = intent.parcelableArrayExtraCompat<NdefMessage>(NfcAdapter.EXTRA_NDEF_MESSAGES)?.firstOrNull()
        val content = NfcTagContentParser.fromTag(tag, message)

        if (waitingForScan.get()) {
            _tagScans.tryEmit(content)
            _rawTagScans.tryEmit(tag)
            return
        }

        if (content.source == NfcTagContent.TagSource.NDEF_URI) {
            ProfileLinkCodec.parseProfileId(content.value)?.let(onBackgroundProfileScan)
        }
    }
}

private inline fun <reified T : Parcelable> Intent.parcelableExtraCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key) as? T
    }

private inline fun <reified T : Parcelable> Intent.parcelableArrayExtraCompat(key: String): Array<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        (getParcelableArrayExtra(key) as? Array<Parcelable>)?.map { it as T }?.toTypedArray()
    }
