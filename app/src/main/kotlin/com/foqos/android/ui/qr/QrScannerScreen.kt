package com.foqos.android.ui.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * Full-bleed QR/barcode scanner built on zxing-android-embedded's [DecoratedBarcodeView], with
 * CAMERA runtime permission handling and a torch toggle - Android counterpart of iOS
 * `LabeledCodeScannerView` (`CodeScannerView` + flashlight button). Used for strategy QR
 * start/stop scans and physical-unlock QR registration.
 *
 * [onCode] is invoked once per continuous-decode result; callers are responsible for dismissing
 * this screen (it keeps decoding until removed from composition).
 */
@Composable
fun QrScannerScreen(
    onCode: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Scan a QR code",
    subtitle: String = "Point your camera at a QR code or barcode.",
) {
    val context = LocalContext.current
    val currentOnCode by rememberUpdatedState(onCode)

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))

        if (hasCameraPermission) {
            CameraScannerSurface(onCode = currentOnCode)
        } else {
            CameraPermissionRationale(onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) })
        }
    }
}

@Composable
private fun CameraScannerSurface(onCode: (String) -> Unit) {
    var barcodeView by remember { mutableStateOf<DecoratedBarcodeView?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        AndroidView(
            factory = { ctx ->
                DecoratedBarcodeView(ctx).apply {
                    decodeContinuous(object : BarcodeCallback {
                        override fun barcodeResult(result: BarcodeResult) {
                            result.text?.let(onCode)
                        }

                        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) = Unit
                    })
                    barcodeView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Surface(
            modifier = Modifier
                .padding(20.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ) {
            Box(
                modifier = Modifier.padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (torchOn) "🔦" else "🔅",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clickable {
                        torchOn = !torchOn
                        if (torchOn) barcodeView?.setTorchOn() else barcodeView?.setTorchOff()
                    },
                )
            }
        }
    }

    DisposableEffect(barcodeView, lifecycleOwner) {
        val view = barcodeView
        if (view == null) {
            onDispose {}
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> view.resume()
                    Lifecycle.Event.ON_PAUSE -> view.pause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            view.resume()
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                view.pause()
            }
        }
    }
}

@Composable
private fun CameraPermissionRationale(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "📷", style = MaterialTheme.typography.displaySmall)
        Text(text = "Camera access is required to scan codes.", style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Button(onClick = onRequestPermission) {
            Text(text = "Grant camera access")
        }
    }
}
