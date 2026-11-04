package com.foqos.android.ui.activitylab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Calls a user-owned HTTPS relay. Gemini credentials never live in the Android APK.
 * No image is uploaded unless the user explicitly starts a Gemini test.
 */
object ActivityGeminiRelay {
    suspend fun judge(
        endpoint: String,
        bearer: String,
        activity: String,
        mode: String,
        photos: List<File>,
        video: File?,
        intervalMillis: Long,
    ): ActivityVerdict = withContext(Dispatchers.IO) {
        require(endpoint.startsWith("https://", ignoreCase = true)) {
            "HTTPSの中継サーバーURLを指定してね"
        }
        val request = JSONObject().put("activity", activity).put("mode", mode)
        if (video != null) {
            require(video.length() <= 14_000_000) { "動画が14MBを超えたため送信を中止。撮影時間を短くしてね。" }
            request.put("video", JSONObject().put("mimeType", "video/mp4")
                .put("data", Base64.encodeToString(video.readBytes(), Base64.NO_WRAP)))
        } else {
            require(photos.isNotEmpty()) { "送信する撮影画像がないよ" }
            val array = JSONArray()
            photos.forEachIndexed { index, file ->
                array.put(JSONObject()
                    .put("timestampMs", index * intervalMillis)
                    .put("mimeType", "image/jpeg")
                    .put("data", compressForUpload(file)))
            }
            request.put("frames", array)
        }
        val body = request.toString().toByteArray(Charsets.UTF_8)
        require(body.size <= 18_000_000) { "送信画像が多すぎるため中止。枚数を減らしてね。" }
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (bearer.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $bearer")
            connection.outputStream.use { it.write(body) }
            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) connection.inputStream
                else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299)
                error("中継サーバーエラー（HTTP $responseCode）: ${responseText.take(200)}")
            val json = JSONObject(responseText)
            ActivityVerdict(
                status = json.optString("status", "判断不能"),
                reason = json.optString("reason", "結果の説明がありません"),
                evidence = json.optString("evidence", ""),
                inputTokens = json.optInt("inputTokens", 0),
                outputTokens = json.optInt("outputTokens", 0),
                estimatedYen = if (json.has("estimatedYen")) json.optDouble("estimatedYen") else null,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun compressForUpload(file: File): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > 768 || opts.outHeight / sample > 768) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: error("画像を読み込めなかった")
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 68, output)
        bitmap.recycle()
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
