package com.wyltek.wallet.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.wyltek.wallet.ui.theme.*
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onBack: () -> Unit = {},
    onScanned: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Scan QR Code") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        if (!hasPermission) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Camera permission is required to scan QR codes",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            return
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val previewView = remember { PreviewView(context) }
            val noAddressNotice = remember { mutableStateOf<String?>(null) }

            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
            // Drawn after the camera preview so it is not covered by its surface
            noAddressNotice.value?.let { notice ->
                Text(
                    text = notice,
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 48.dp)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Overlay with scan frame
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(250.dp)
                        .background(Color.Transparent)
                ) {
                    // Corner markers
                    val strokeWidth = 3.dp
                    val cornerLength = 32.dp
                    val color = NeonCyan

                    // Top-left
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.size(cornerLength, strokeWidth).background(color).align(Alignment.TopStart))
                        Box(modifier = Modifier.size(strokeWidth, cornerLength).background(color).align(Alignment.TopStart))
                    }
                    // Top-right
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.size(cornerLength, strokeWidth).background(color).align(Alignment.TopEnd))
                        Box(modifier = Modifier.size(strokeWidth, cornerLength).background(color).align(Alignment.TopEnd))
                    }
                    // Bottom-left
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.size(cornerLength, strokeWidth).background(color).align(Alignment.BottomStart))
                        Box(modifier = Modifier.size(strokeWidth, cornerLength).background(color).align(Alignment.BottomStart))
                    }
                    // Bottom-right
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.size(cornerLength, strokeWidth).background(color).align(Alignment.BottomEnd))
                        Box(modifier = Modifier.size(strokeWidth, cornerLength).background(color).align(Alignment.BottomEnd))
                    }
                }
            }

            // Camera setup
            LaunchedEffect(previewView) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder()
                        .build()
                        .also { it.setSurfaceProvider(previewView.getSurfaceProvider()) }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setResolutionSelector(
                            androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                                        Size(1280, 720),
                                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                    )
                                )
                                .build()
                        )
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val scanner = MultiFormatReader().apply {
                        setHints(
                            mapOf(
                                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                DecodeHintType.CHARACTER_SET to "UTF-8",
                                DecodeHintType.TRY_HARDER to true
                            )
                        )
                    }

                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        val yPlane = imageProxy.planes[0]
                        val buffer = yPlane.buffer
                        val raw = ByteArray(buffer.remaining())
                        buffer.get(raw)
                        val packed = QrFrame.luminance(raw, imageProxy.width, imageProxy.height, yPlane.rowStride)
                        val frame = QrFrame.downscale(packed, imageProxy.width, imageProxy.height)
                        val source = PlanarYUVLuminanceSource(
                            frame.bytes, frame.width, frame.height,
                            0, 0, frame.width, frame.height, false
                        )
                        // Dark-themed wallets (JoyID) show light-on-dark codes: try the inverted image too.
                        val text = listOf(source, source.invert()).firstNotNullOfOrNull { lum ->
                            try {
                                scanner.decodeWithState(BinaryBitmap(HybridBinarizer(lum))).text
                            } catch (_: NotFoundException) {
                                null
                            } catch (_: Exception) {
                                null
                            } finally {
                                scanner.reset()
                            }
                        }
                        if (text.isNullOrBlank()) {
                            noAddressNotice.value = null
                        } else {
                            val address = QrAddress.extract(text)
                            if (address != null) {
                                imageProxy.close()
                                imageAnalysis.clearAnalyzer()
                                // Navigation must happen on the main thread.
                                mainHandler.post { onScanned(address) }
                                return@setAnalyzer
                            }
                            android.util.Log.d("QrScanner", "decoded QR without a CKB address: ${text.take(80)}")
                            noAddressNotice.value = "No CKB address in that QR code"
                        }
                        imageProxy.close()
                    }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (_: Exception) {
                        // Camera binding failed
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        }
    }
}
