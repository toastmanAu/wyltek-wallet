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

            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

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
                        .setTargetResolution(Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val scanner = MultiFormatReader().apply {
                        setHints(
                            mapOf(
                                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                DecodeHintType.CHARACTER_SET to "UTF-8"
                            )
                        )
                    }

                    imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                        val buffer = imageProxy.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        val source = PlanarYUVLuminanceSource(
                            bytes,
                            imageProxy.width,
                            imageProxy.height,
                            0,
                            0,
                            imageProxy.width,
                            imageProxy.height,
                            false
                        )
                        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

                        try {
                            val result = scanner.decode(binaryBitmap)
                            val text = result.text
                            if (text.isNotBlank()) {
                                // Validate it's a CKB address
                                if (text.startsWith("ckt") || text.startsWith("ckb")) {
                                    imageProxy.close()
                                    onScanned(text)
                                    return@setAnalyzer
                                }
                            }
                        } catch (_: NotFoundException) {
                            // No QR found in this frame
                        } catch (_: Exception) {
                            // Other decoding errors
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
