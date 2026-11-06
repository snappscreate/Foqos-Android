package com.foqos.android.ui.activitylab

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
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

    /**
     * Developer-only direct Gemini test: key is entered at runtime and never persisted.
     * Not suitable for distributed APKs. Prefer the authenticated relay for normal use.
     */
    suspend fun judgeDirect(
        apiKey: String,
        model: String,
        activity: String,
        photos: List<File>,
        video: File?,
        intervalMillis: Long,
    ): ActivityVerdict = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Gemini APIキーを入力してね" }
        require(model in SUPPORTED_MODELS) { "未対応のモデル名だよ" }
        val parts = JSONArray().put(JSONObject().put("text",
            "時系列の撮影データを見て「${activity.take(160)}」を本当に行ったか判定。" +
                "姿勢だけで断定せず、明確な動きや継続の証拠が不足する場合は判断不能。"))
        if (video != null) {
            require(video.length() <= 14_000_000) { "動画が14MBを超えたため送信を中止したよ" }
            parts.put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "video/mp4")
                .put("data", Base64.encodeToString(video.readBytes(), Base64.NO_WRAP))))
        } else {
            photos.forEachIndexed { index, frame ->
                parts.put(JSONObject().put("text", "撮影時刻：${index * intervalMillis}ms"))
                parts.put(JSONObject().put("inlineData", JSONObject()
                    .put("mimeType", "image/jpeg")
                    .put("data", compressForUpload(frame))))
            }
        }
        val schema = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("status", JSONObject().put("type", "STRING")
                    .put("enum", JSONArray(listOf("一致", "不一致", "判断不能"))))
                .put("reason", JSONObject().put("type", "STRING"))
                .put("evidence", JSONObject().put("type", "STRING")))
            .put("required", JSONArray(listOf("status", "reason", "evidence")))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.1)
                .put("responseMimeType", "application/json").put("responseSchema", schema))
            .toString().toByteArray(Charsets.UTF_8)
        require(body.size < 18_000_000) { "撮影データが大きすぎるよ。枚数を減らしてね" }
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.outputStream.use { it.write(body) }
            val statusCode = connection.responseCode
            val raw = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) error("Gemini HTTP $statusCode: ${raw.take(180)}")
            val response = JSONObject(raw)
            val candidate = response.getJSONArray("candidates").getJSONObject(0)
            val text = candidate.getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text")
            val answer = JSONObject(text)
            val usage = response.optJSONObject("usageMetadata") ?: JSONObject()
            val inputs = usage.optInt("promptTokenCount")
            val outputs = usage.optInt("candidatesTokenCount") + usage.optInt("thoughtsTokenCount")
            ActivityVerdict(
                status = answer.optString("status", "判断不能"),
                reason = answer.optString("reason", "理由を取得できなかった"),
                evidence = answer.optString("evidence", ""),
                inputTokens = inputs,
                outputTokens = outputs,
                // Published paid standard price; free-tier requests may be free.
                estimatedYen = ((inputs * if (model == "gemini-3.5-flash") 1.50 else 0.30) +
                    (outputs * if (model == "gemini-3.5-flash") 9.00 else 2.50)) * 160.0 / 1_000_000.0,
            )
        } finally {
            connection.disconnect()
        }
    }

    private val SUPPORTED_MODELS = setOf("gemini-3.5-flash", "gemini-3.5-flash-lite")

    private fun compressForUpload(file: File): String {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > 768 || opts.outHeight / sample > 768) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
        }) ?: error("画像を読み込めなかった")
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val corrected = if (degrees == 0f) bitmap else Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees) }, true)
        val output = ByteArrayOutputStream()
        corrected.compress(Bitmap.CompressFormat.JPEG, 68, output)
        if (corrected !== bitmap) corrected.recycle()
        bitmap.recycle()
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
