package com.diplomka.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.diplomka.app.vision.DroneVisionPipeline
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DiplomkaApp() {
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        if (!cameraPermission.status.isGranted) {
            cameraPermission.launchPermissionRequest()
        }
    }

    if (cameraPermission.status.isGranted) {
        DroneNavigationScreen()
    } else {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF0D1117)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Нужен доступ к камере", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { cameraPermission.launchPermissionRequest() }) {
                    Text("Разрешить камеру")
                }
            }
        }
    }
}

@Composable
fun DroneNavigationScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pipeline = remember { DroneVisionPipeline() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    var result by remember { mutableStateOf<DroneVisionPipeline.FrameResult?>(null) }
    var showLog by remember { mutableStateOf(true) }
    var isRunning by remember { mutableStateOf(true) }

    val borderColor by animateColorAsState(
        if (result?.stopSignal == true) Color(0xFFDA3633) else Color(0xFF238636),
        label = "border"
    )

    Box(Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (showLog) 0.62f else 1f)
                .border(4.dp, borderColor)
        ) {
            CameraPreviewWithAnalysis(
                lifecycleOwner = lifecycleOwner,
                isRunning = isRunning,
                analysisExecutor = analysisExecutor,
                onFrame = { bitmap ->
                    if (isRunning) {
                        val r = pipeline.process(bitmap)
                        result = r
                    }
                }
            )

            result?.let { r ->
                DetectionOverlay(
                    detections = r.detections,
                    targetX = r.targetX,
                    targetY = r.targetY,
                    stopSignal = r.stopSignal,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color(0xCC0D1117), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    "DIPLOMKA — Drone Delivery Sim",
                    color = Color(0xFF58A6FF),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                result?.let { r ->
                    Text(
                        "Скорость: ${\"%.1f\".format(r.estimatedSpeedMps)} м/с  •  Путь: ${\"%.1f\".format(r.totalDistanceM)} м",
                        color = Color(0xFFE6EDF3),
                        fontSize = 12.sp
                    )
                    Text(
                        "Погода: ${r.weather}",
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp
                    )
                }
            }

            if (result?.stopSignal == true) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(
                        "СТОП!",
                        color = Color(0xFFFF6B6B),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { showLog = !showLog },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color(0xFF21262D)
                    )
                ) {
                    Icon(
                        if (showLog) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = Color.White
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (showLog) "Скрыть лог" else "Лог", color = Color.White)
                }
                FilledTonalButton(
                    onClick = {
                        isRunning = !isRunning
                        if (!isRunning) pipeline.reset()
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (isRunning) Color(0xFFDA3633) else Color(0xFF238636)
                    )
                ) {
                    Icon(
                        if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }
        }

        if (showLog) {
            CommandLogPanel(
                commands = result?.commands ?: emptyList(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.38f)
            )
        }
    }
}

@Composable
fun CommandLogPanel(
    commands: List<DroneVisionPipeline.NavigationCommand>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(commands.firstOrNull()?.timestamp) {
        if (commands.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier
            .background(Color(0xFF161B22))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Terminal, null, tint = Color(0xFF58A6FF), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Команды в реальном времени (не муляж)",
                color = Color(0xFF58A6FF),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        Divider(color = Color(0xFF30363D))
        Spacer(Modifier.height(4.dp))

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(commands) { cmd ->
                val color = when (cmd.severity) {
                    DroneVisionPipeline.NavigationCommand.Severity.STOP -> Color(0xFFFF7B72)
                    DroneVisionPipeline.NavigationCommand.Severity.WARN -> Color(0xFFD29922)
                    DroneVisionPipeline.NavigationCommand.Severity.NAV -> Color(0xFF7EE787)
                    else -> Color(0xFF8B949E)
                }
                Text(
                    text = "› ${cmd.text}",
                    color = color,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun DetectionOverlay(
    detections: List<DroneVisionPipeline.Detection>,
    targetX: Float,
    targetY: Float,
    stopSignal: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        for (d in detections) {
            val color = when (d.type) {
                "person" -> Color(0xFFFF4444)
                "vehicle" -> Color(0xFF4488FF)
                "building" -> Color(0xFFFFCC00)
                "motion" -> Color(0xFFFF66FF)
                else -> Color.White
            }
            val left = d.rect.left
            val top = d.rect.top
            val right = d.rect.right
            val bottom = d.rect.bottom

            drawRect(
                color = color.copy(alpha = 0.25f),
                topLeft = Offset(left, top),
                size = Size((right - left).coerceAtLeast(8f), (bottom - top).coerceAtLeast(8f))
            )
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size((right - left).coerceAtLeast(8f), (bottom - top).coerceAtLeast(8f)),
                style = Stroke(width = 3f)
            )
        }

        val tx = targetX * w
        val ty = targetY * h
        drawCircle(Color(0xFF00FF88), radius = 14f, center = Offset(tx, ty))
        drawCircle(Color.White, radius = 6f, center = Offset(tx, ty))
        val start = Offset(w / 2f, h * 0.92f)
        drawLine(Color(0xFF00FF88), start, Offset(tx, ty), strokeWidth = 4f)

        if (stopSignal) {
            drawRect(
                color = Color(0x44FF0000),
                topLeft = Offset(0f, 0f),
                size = Size(w, h)
            )
        }
    }
}

@Composable
fun CameraPreviewWithAnalysis(
    lifecycleOwner: LifecycleOwner,
    isRunning: Boolean,
    analysisExecutor: java.util.concurrent.Executor,
    onFrame: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    ) { view ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also { it.setSurfaceProvider(view.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                if (!isRunning) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                try {
                    val bitmap = imageProxy.toBitmap()
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val finalBitmap = if (rotation != 0) {
                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else bitmap

                    onFrame(finalBitmap)
                } catch (e: Exception) {
                    Log.e("Diplomka", "Analysis error", e)
                } finally {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e("Diplomka", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

fun ImageProxy.toBitmap(): Bitmap {
    val plane = planes[0]
    val buffer = plane.buffer
    buffer.rewind()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(buffer)
    return bitmap
}
