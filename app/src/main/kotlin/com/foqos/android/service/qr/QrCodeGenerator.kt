package com.foqos.android.service.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.foqos.android.service.nfc.ProfileLinkCodec
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a profile's `foqos://profile/{uuid}` link as a QR code - Android counterpart of iOS
 * `QRCodeView.generateQRCode` (`CIFilter.qrCodeGenerator`, `M` error correction).
 */
object QrCodeGenerator {

    /** Quiet zone width in QR modules around the code, matching common scanner expectations. */
    private const val QUIET_ZONE_MODULES = 2

    /**
     * Pure zxing-core encode step (no Android dependency, directly unit-testable): the [BitMatrix]
     * for [profileId]'s profile link, [sizePx] square, `M` error correction.
     */
    fun profileQrMatrix(profileId: String, sizePx: Int): BitMatrix {
        val content = ProfileLinkCodec.buildProfileUri(profileId)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
        )
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    /** Renders [profileId]'s QR code as an Android [Bitmap], [sizePx] square (black modules on white). */
    fun profileQrBitmap(profileId: String, sizePx: Int): Bitmap {
        val matrix = profileQrMatrix(profileId, sizePx)
        val bitmap = createBitmap(matrix.width, matrix.height)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bitmap[x, y] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return bitmap
    }
}
