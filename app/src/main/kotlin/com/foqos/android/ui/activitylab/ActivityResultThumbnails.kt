package com.foqos.android.ui.activitylab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import java.io.File

/** Only reads local temporary captures; never changes or uploads the source files. */
@Composable
internal fun ActivityResultThumbnails(files: List<File>) {
    val thumbnails = remember(files) {
        files.flatMap { file ->
            if (!file.exists()) emptyList() else if (file.extension == "mp4") {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        val durationMs = retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION
                        )?.toLongOrNull() ?: 0L
                        listOf(0.1, 0.5, 0.9).mapNotNull { fraction ->
                            val timestampUs = (durationMs * fraction * 1000).toLong()
                            retriever.getFrameAtTime(
                                timestampUs, MediaMetadataRetriever.OPTION_CLOSEST
                            )?.let { image ->
                                val small = Bitmap.createScaledBitmap(image, 160, 100, true)
                                if (small !== image) image.recycle()
                                small.asImageBitmap()
                            }
                        }
                    } finally { retriever.release() }
                }.getOrDefault(emptyList())
            } else {
                runCatching {
                    BitmapFactory.decodeFile(
                        file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 }
                    )?.asImageBitmap()
                }.getOrNull()?.let(::listOf).orEmpty()
            }
        }
    }
    if (thumbnails.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (files.any { it.extension == "mp4" })
                    "動画の代表フレーム（序盤・中盤・終盤）"
                else "撮影画像（時系列順）",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                thumbnails.forEachIndexed { index, image ->
                    Image(
                        bitmap = image,
                        contentDescription = "撮影サムネイル ${index + 1}",
                        modifier = Modifier.size(width = 104.dp, height = 78.dp),
                    )
                }
            }
        }
    }
}
