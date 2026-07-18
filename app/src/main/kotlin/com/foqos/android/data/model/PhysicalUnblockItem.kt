package com.foqos.android.data.model

import java.net.URI
import java.net.URISyntaxException
import java.util.UUID
import kotlinx.serialization.Serializable

/** Type of physical token that can unblock a profile. */
@Serializable
enum class PhysicalUnblockType {
    NFC,
    QR_CODE,
}

/**
 * A physical NFC tag or QR code that can unblock a profile, mirroring iOS `PhysicalUnblockItem`.
 * A profile may have multiple items of either type.
 */
@Serializable
data class PhysicalUnblockItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: PhysicalUnblockType,
    val codeValue: String,
) {
    companion object {
        /**
         * Normalizes a scanned/entered code value: trims whitespace, and - if the value parses
         * as a URL with both a scheme and a host - lowercases the scheme and host and strips a
         * bare trailing slash (e.g. "HTTPS://Example.com/" -> "https://example.com"). Values that
         * are not URLs (e.g. raw NFC tag ids) are returned trimmed but otherwise unchanged.
         */
        fun normalizeCodeValue(codeValue: String): String {
            val trimmed = codeValue.trim()
            if (trimmed.isEmpty()) return trimmed

            return try {
                val uri = URI(trimmed)
                val scheme = uri.scheme
                val host = uri.host
                if (scheme == null || host == null) {
                    trimmed
                } else {
                    val path = uri.path ?: ""
                    val normalizedPath = if (path == "/" && uri.query == null && uri.fragment == null) {
                        ""
                    } else {
                        path
                    }

                    URI(
                        scheme.lowercase(),
                        uri.userInfo,
                        host.lowercase(),
                        uri.port,
                        normalizedPath,
                        uri.query,
                        uri.fragment,
                    ).toString()
                }
            } catch (e: URISyntaxException) {
                trimmed
            } catch (e: IllegalArgumentException) {
                trimmed
            }
        }
    }
}
