package com.foqos.android.service.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import java.net.URI
import java.net.URISyntaxException

/**
 * The payload read off a scanned NFC tag, and where it came from - mirrors iOS `NFCResult`
 * (`id`/`url`), but collapsed into a single [value] since Android callers only ever care about the
 * one string to feed into [com.foqos.android.domain.strategy.StrategyInput.Code].
 */
data class NfcTagContent(val value: String, val source: TagSource) {

    /** Where [NfcTagContent.value] came from. */
    enum class TagSource {
        /** An NDEF URI record on the tag (a `foqos://` or `https://foqos.app` profile link). */
        NDEF_URI,

        /** No usable NDEF URI record was found - fell back to the tag's own hardware UID. */
        TAG_ID,
    }
}

/**
 * Parses a scanned [Tag] (+ its NDEF message, if any) into [NfcTagContent], preferring a
 * foqos profile URI record and falling back to the tag's hardware id - mirrors iOS
 * `NFCScannerUtil.readMiFareTag`/`readISO15693Tag` (NDEF URL if present, else `identifier.hexEncodedString()`).
 */
object NfcTagContentParser {

    /** Builds [NfcTagContent] from a scanned [tag] and the [NdefMessage] read off it, if any. */
    fun fromTag(tag: Tag, ndefMessage: NdefMessage?): NfcTagContent {
        val uri = ndefMessage?.let { findFoqosUri(it.records.toList()) }
        return if (uri != null) {
            NfcTagContent(uri, NfcTagContent.TagSource.NDEF_URI)
        } else {
            NfcTagContent(hexEncodeTagId(tag.id), NfcTagContent.TagSource.TAG_ID)
        }
    }

    /**
     * Pure, testable core: scans [records] for the first well-known/absolute URI record whose
     * value [ProfileLinkCodec.parseProfileId] recognizes as a foqos profile link. Returns the raw
     * URI string (not just the profile id) so callers can still feed the whole value through
     * [StrategyInput.Code][com.foqos.android.domain.strategy.StrategyInput.Code] the same way a
     * TAG_ID fallback would.
     */
    fun findFoqosUri(records: List<NdefRecord>): String? =
        records.asSequence()
            .mapNotNull { runCatching { it.toUri() }.getOrNull() }
            .map { it.toString() }
            .firstOrNull { ProfileLinkCodec.parseProfileId(it) != null }

    /**
     * Lowercase, unseparated hex encoding of a tag's hardware UID (e.g. `04a2ff19`). Note: iOS's
     * `Data.hexEncodedString()` (`NFCScannerUtil.swift`) uses *uppercase* hex via `%02hhX` - this
     * intentionally diverges to lowercase per the Android spec, so a tag's raw-UID fallback value
     * will differ in case between the two platforms for the same physical tag. This only matters
     * if a `physicalUnblockItems` entry recorded on one platform is compared byte-for-byte against
     * a scan performed on the other; [com.foqos.android.data.model.PhysicalUnblockItem.normalizeCodeValue]
     * does not case-fold non-URL values, so cross-platform raw-UID items would need normalizing to
     * match - flagged here rather than silently "fixed" since this is a public cross-platform
     * project and the discrepancy is worth a deliberate decision later.
     */
    fun hexEncodeTagId(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * Builds and parses the `foqos://profile/{uuid}` deep link (and its iOS-written
 * `https://foqos.app/profile/{uuid}` universal-link twin) that both NFC tags and QR codes carry.
 */
object ProfileLinkCodec {
    private const val SCHEME = "foqos"
    private const val HTTPS_SCHEME = "https"
    private const val HTTPS_HOST = "foqos.app"
    private const val PATH_SEGMENT = "profile"
    private const val NAVIGATE_PATH_SEGMENT = "navigate"

    /** Builds the canonical `foqos://profile/{profileId}` link written to Android-created tags/QR codes. */
    fun buildProfileUri(profileId: String): String = "$SCHEME://$PATH_SEGMENT/$profileId"

    /**
     * Extracts the profile id from [uriString] if it is a recognized profile link: either
     * `foqos://profile/{uuid}` or `https://foqos.app/profile/{uuid}` (iOS-written tags/links),
     * tolerating a trailing slash on either form. Returns null for anything else (wrong
     * scheme/host, missing/blank id, extra path segments, or an unparseable string).
     */
    fun parseProfileId(uriString: String): String? = parseId(uriString, PATH_SEGMENT)

    /**
     * Phase 8 addition, iOS parity: extracts the profile id from a "just open the app, don't
     * toggle anything" link - `foqos://navigate/{uuid}` or `https://foqos.app/navigate/{uuid}`.
     * Callers use this only to validate/log the link; unlike [parseProfileId] its result is never
     * fed into [com.foqos.android.domain.session.SessionManager.toggleFromDeeplink].
     */
    fun parseNavigateProfileId(uriString: String): String? = parseId(uriString, NAVIGATE_PATH_SEGMENT)

    private fun parseId(uriString: String, pathSegment: String): String? {
        val trimmedInput = uriString.trim()
        if (trimmedInput.isEmpty()) return null

        val uri = try {
            URI(trimmedInput)
        } catch (e: URISyntaxException) {
            return null
        }

        val scheme = uri.scheme?.lowercase() ?: return null
        return when (scheme) {
            SCHEME -> {
                // foqos://{pathSegment}/{id} parses with host="{pathSegment}", path="/{id}".
                if (uri.host?.lowercase() != pathSegment) return null
                singlePathSegment(uri.path)
            }
            HTTPS_SCHEME -> {
                if (uri.host?.lowercase() != HTTPS_HOST) return null
                val segments = uri.path?.trim('/')?.split('/').orEmpty()
                if (segments.size != 2 || segments[0] != pathSegment) return null
                segments[1].takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }

    private fun singlePathSegment(path: String?): String? {
        val trimmed = path?.trim('/') ?: return null
        return trimmed.takeIf { it.isNotBlank() && !it.contains('/') }
    }
}
