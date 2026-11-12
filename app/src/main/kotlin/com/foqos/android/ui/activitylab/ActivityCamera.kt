package com.foqos.android.ui.activitylab

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * A single CameraX pipeline owned by the test page, never by Foqos's blocking service.
 * All recordings are silent; the experiment does not request microphone access.
 */
class ActivityCamera(private val context: Context) {
    val preview = PreviewView(context).apply {
        // TextureView respects Compose overlay ordering and the 220dp preview bounds.
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }
    private val executor: Executor = ContextCompat.getMainExecutor(context)
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    // Motion recognition does not require HD. Low bitrate keeps even 60s clips small.
    private val videoCapture = VideoCapture.withOutput(
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD))
            .setTargetVideoEncodingBitRate(750_000)
            .build()
    )
    // Long-running intermittent mode: ~480p, target ~500kbps, microphone disabled.
    // CameraX encoding bitrate is a target; actual size depends on the device encoder.
    private val compressedVideoCapture = VideoCapture.withOutput(
        Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD))
            .setTargetVideoEncodingBitRate(500_000)
            .build()
    )
    private var selectedVideoCapture: VideoCapture<Recorder> = videoCapture
    private var provider: ProcessCameraProvider? = null
    private var activeRecording: Recording? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stopTask: Runnable? = null
    private var recordingStartedAt = 0L
    private var remainingMillis = 0L
    private var paused = false

    val isRecordingPaused: Boolean get() = paused
    val remainingRecordingMillis: Long get() =
        if (paused || activeRecording == null) remainingMillis
        else (remainingMillis - (android.os.SystemClock.elapsedRealtime() - recordingStartedAt))
            .coerceAtLeast(0L)

    private fun clearStopTimer() {
        stopTask?.let(handler::removeCallbacks)
        stopTask = null
    }

    fun pauseRecording() {
        val recording = activeRecording ?: return
        if (paused) return
        remainingMillis = remainingRecordingMillis
        clearStopTimer()
        paused = true
        recording.pause()
    }

    fun resumeRecording() {
        val recording = activeRecording ?: return
        if (!paused) return
        paused = false
        recording.resume()
        scheduleStop()
    }

    /** Finalize the currently captured portion and continue to Gemini verdict. */
    fun finishRecordingNow() {
        if (activeRecording == null) return
        clearStopTimer()
        activeRecording?.stop()
    }

    private fun scheduleStop() {
        clearStopTimer()
        recordingStartedAt = android.os.SystemClock.elapsedRealtime()
        val task = Runnable { activeRecording?.stop() }
        stopTask = task
        if (remainingMillis > 0L) handler.postDelayed(task, remainingMillis)
    }


    suspend fun bind(owner: LifecycleOwner, front: Boolean, videoMode: Boolean,
        intermittent: Boolean = false) {
        val cameraProvider = suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { future.get() }
                    .onSuccess { if (continuation.isActive) continuation.resume(it) }
                    .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
            }, executor)
        }
        provider = cameraProvider
        cameraProvider.unbindAll()
        val previewUseCase = Preview.Builder().build().also {
            it.setSurfaceProvider(preview.surfaceProvider)
        }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA
        selectedVideoCapture = if (intermittent) compressedVideoCapture else videoCapture
        if (videoMode) cameraProvider.bindToLifecycle(owner, selector, previewUseCase, selectedVideoCapture)
        else cameraProvider.bindToLifecycle(owner, selector, previewUseCase, imageCapture)
    }

    suspend fun captureFile(): File = suspendCancellableCoroutine { continuation ->
        val file = File.createTempFile("activity_frame_", ".jpg", context.cacheDir)
        val output = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                if (continuation.isActive) continuation.resume(file)
                else file.delete()
            }
            override fun onError(error: ImageCaptureException) {
                file.delete()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        })
        continuation.invokeOnCancellation { file.delete() }
    }

    suspend fun recordFor(durationMillis: Long): File = suspendCancellableCoroutine { continuation ->
        val file = File.createTempFile("activity_video_", ".mp4", context.cacheDir)
        val output = FileOutputOptions.Builder(file).build()
        val pending: PendingRecording = selectedVideoCapture.output.prepareRecording(context, output)
        paused = false
        remainingMillis = durationMillis
        activeRecording = pending.start(executor) { event ->
            when (event) {
                is VideoRecordEvent.Status -> {
                    // Gemini inline uploads cannot exceed 14 MB. Stop before the limit
                    // even when the user selected unlimited *time*.
                    if (event.recordingStats.numBytesRecorded >= 12_000_000L) {
                        finishRecordingNow()
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    clearStopTimer()
                    activeRecording = null
                    remainingMillis = 0L
                    paused = false
                    if (event.hasError()) {
                        file.delete()
                        if (continuation.isActive)
                            continuation.resumeWithException(IllegalStateException(
                                "動画撮影に失敗: ${event.error}", event.cause))
                    } else if (continuation.isActive) continuation.resume(file)
                    else file.delete()
                }
            }
        }
        scheduleStop()
        continuation.invokeOnCancellation {
            clearStopTimer()
            activeRecording?.stop()
            activeRecording = null
            file.delete()
        }
    }

    fun close() {
        clearStopTimer()
        activeRecording?.stop()
        activeRecording = null
        provider?.unbindAll()
        provider = null
    }
}
