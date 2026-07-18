package com.foqos.android.service.qr

import com.foqos.android.service.nfc.ProfileLinkCodec
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Test

/**
 * Pure zxing-core round-trip (encode then decode) - no Android/Bitmap dependency, so this runs as
 * a plain JVM unit test. Decodes through [QRCodeReader] + [HybridBinarizer] (the same finder-
 * pattern-locating path a real camera scan goes through), rather than the lower-level
 * `Decoder.decode(BitMatrix)` which assumes a zero-margin, 1:1 module grid - [profileQrMatrix]
 * intentionally keeps its quiet zone (real scanners need it), so this is the realistic round trip.
 */
class QrCodeGeneratorTest {

    private val profileId = "550e8400-e29b-41d4-a716-446655440000"

    @Test
    fun `encoded qr decodes back to the profile uri`() {
        val matrix = QrCodeGenerator.profileQrMatrix(profileId, sizePx = 200)

        assertThat(decode(matrix)).isEqualTo(ProfileLinkCodec.buildProfileUri(profileId))
    }

    @Test
    fun `matrix is square and matches the requested size`() {
        val matrix = QrCodeGenerator.profileQrMatrix(profileId, sizePx = 300)

        assertThat(matrix.width).isEqualTo(300)
        assertThat(matrix.height).isEqualTo(300)
    }

    @Test
    fun `different profile ids encode to different content`() {
        val a = decode(QrCodeGenerator.profileQrMatrix("aaaa", sizePx = 150))
        val b = decode(QrCodeGenerator.profileQrMatrix("bbbb", sizePx = 150))

        assertThat(a).isNotEqualTo(b)
    }

    private fun decode(matrix: BitMatrix): String {
        val bitmap = BinaryBitmap(HybridBinarizer(BitMatrixLuminanceSource(matrix)))
        return QRCodeReader().decode(bitmap).text
    }
}

/** Adapts a [BitMatrix] (black=set module) into a [LuminanceSource] so zxing's real scan-decode path can read it. */
private class BitMatrixLuminanceSource(private val matrix: BitMatrix) : LuminanceSource(matrix.width, matrix.height) {

    override fun getRow(y: Int, row: ByteArray?): ByteArray {
        val out = if (row != null && row.size >= width) row else ByteArray(width)
        for (x in 0 until width) {
            out[x] = if (matrix.get(x, y)) BLACK else WHITE
        }
        return out
    }

    override fun getMatrix(): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + x] = if (matrix.get(x, y)) BLACK else WHITE
            }
        }
        return out
    }

    companion object {
        private const val BLACK: Byte = 0x00
        private const val WHITE: Byte = 0xFF.toByte()
    }
}
