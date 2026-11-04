package com.foqos.android.ui.activitylab

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.acos
import kotlin.math.sqrt
import kotlinx.coroutines.suspendCancellableCoroutine

data class ActivityVerdict(
    val status: String,
    val reason: String,
    val evidence: String = "",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val estimatedYen: Double? = null,
    val elapsedMs: Long = 0,
)

/** Offline motion validation. Other activities are explicitly unsupported, not guessed. */
object PoseActivityJudge {
    suspend fun judge(context: Context, activity: String, images: List<File>): ActivityVerdict {
        val pushUp = listOf("腕立て", "push-up", "pushup").any { activity.contains(it, true) }
        val squat = listOf("スクワット", "squat").any { activity.contains(it, true) }
        if (!pushUp && !squat) return ActivityVerdict(
            "判定対象外", "端末内姿勢判定は現在、腕立て伏せとスクワットに対応。読書などはGeminiモードを使ってね。")
        if (images.size < 4) return ActivityVerdict("判断不能", "姿勢の変化を見るには最低4枚の画像が必要。")
        val detector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE).build()
        )
        return try {
            val angles = mutableListOf<Double>()
            for (file in images) {
                val image = InputImage.fromFilePath(context, Uri.fromFile(file))
                val pose = suspendCancellableCoroutine<Pose> { continuation ->
                    detector.process(image)
                        .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                        .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                }
                val left = if (pushUp)
                    angle(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW,
                        PoseLandmark.LEFT_WRIST)
                    else angle(pose, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE,
                        PoseLandmark.LEFT_ANKLE)
                val right = if (pushUp)
                    angle(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW,
                        PoseLandmark.RIGHT_WRIST)
                    else angle(pose, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE,
                        PoseLandmark.RIGHT_ANKLE)
                listOfNotNull(left, right).average().takeIf { !it.isNaN() }?.let(angles::add)
            }
            if (angles.size < 4) return ActivityVerdict(
                "判断不能", "関節を十分に検出できなかった（有効画像${angles.size}/${images.size}）。全身が映る位置にカメラを置いてね。")
            val minAngle = angles.min()
            val maxAngle = angles.max()
            val range = maxAngle - minAngle
            val threshold = if (pushUp) 35.0 else 45.0
            val low = if (pushUp) 110.0 else 120.0
            val high = if (pushUp) 145.0 else 155.0
            var transitions = 0
            var previous = -1
            for (a in angles) {
                val phase = if (a <= low) 0 else if (a >= high) 1 else -1
                if (phase != -1) {
                    if (previous != -1 && phase != previous) transitions++
                    previous = phase
                }
            }
            val evidence = "有効画像${angles.size}/${images.size}、関節角度${minAngle.toInt()}〜${maxAngle.toInt()}°、上下の切替${transitions}回"
            when {
                range >= threshold && transitions >= 2 ->
                    ActivityVerdict("一致", "姿勢の上下変化を繰り返し確認した。", evidence)
                range < 15.0 ->
                    ActivityVerdict("不一致", "観察中に十分な関節角度の変化がなかった。", evidence)
                else ->
                    ActivityVerdict("判断不能", "動きは検出できたが、指定動作の繰り返しと断定できない。", evidence)
            }
        } finally {
            detector.close()
        }
    }

    private fun angle(pose: Pose, a: Int, b: Int, c: Int): Double? {
        val p = pose.getPoseLandmark(a) ?: return null
        val q = pose.getPoseLandmark(b) ?: return null
        val r = pose.getPoseLandmark(c) ?: return null
        if (listOf(p, q, r).any { it.inFrameLikelihood < 0.55f }) return null
        val ux = (p.position.x - q.position.x).toDouble()
        val uy = (p.position.y - q.position.y).toDouble()
        val vx = (r.position.x - q.position.x).toDouble()
        val vy = (r.position.y - q.position.y).toDouble()
        val magnitude = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
        if (magnitude < 0.00001) return null
        return Math.toDegrees(acos(((ux * vx + uy * vy) / magnitude).coerceIn(-1.0, 1.0)))
    }
}
