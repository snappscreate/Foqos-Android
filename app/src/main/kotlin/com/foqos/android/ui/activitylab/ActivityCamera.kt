package com.foqos.android.ui.activitylab

import android.content.Context
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
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
    val preview = PreviewView(context)
    private val executor: Executor = ContextCompat.getMainExecutor(context)
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    private val videoCapture = VideoCapture.withOutput(Recorder.Builder().build())
    private var provider: ProcessCameraProvider? = null
    private var activeRecording: Recording? = null

    suspend fun bind(owner: LifecycleOwner, front: Boolean) {
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
            it.surfaceProvider = preview.surfaceProvider
        }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA
        cameraProvider.bindToLifecycle(owner, selector, previewUseCase, imageCapture, videoCapture)
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
        val pending: PendingRecording = videoCapture.output.prepareRecording(context, output)
        activeRecording = pending.start(executor) { event ->
            when (event) {
                is VideoRecordEvent.Finalize -> {
                    activeRecording = null
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
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val stop = Runnable { activeRecording?.stop() }
        handler.postDelayed(stop, durationMillis)
        continuation.invokeOnCancellation {
            handler.removeCallbacks(stop)
            activeRecording?.stop()
            activeRecording = null
            file.delete()
        }
    }

    fun close() {
        activeRecording?.stop()
        activeRecording = null
        provider?.unbindAll()
        provider = null
    }
}
