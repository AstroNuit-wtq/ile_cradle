package com.diplomka.app.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun DiplomkaApp() {
    val permissions = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    LaunchedEffect(Unit) {
        if (!permissions.allPermissionsGranted) {
            permissions.launchMultiplePermissionRequest()
        }
    }

    if (permissions.permissions.any { it.status.isGranted && it.permission == Manifest.permission.CAMERA }) {
        DroneNavigationScreen(
            hasLocation = permissions.permissions.any {
                it.status.isGranted && (
                    it.permission == Manifest.permission.ACCESS_FINE_LOCATION ||
                        it.permission == Manifest.permission.ACCESS_COARSE_LOCATION
                    )
            }
        )
    } else {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF0D1117)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Нужен доступ к камере", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissions.launchMultiplePermissionRequest() }) {
                    Text("Разрешить")
                }
            }
        }
    }
}

data class GeoPoint(val lat: Double, val lon: Double)

@Composable
fun DroneNavigationScreen(hasLocation: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pipeline = remember { DroneVisionPipeline() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val lastUiUpdate = remember { AtomicLong(0L) }

    var result by remember { mutableStateOf<DroneVisionPipeline.FrameResult?>(null) }
    var showLog by remember { mutableStateOf(true) }
    var isRunning by remember { mutableStateOf(true) }
    var autoRotate by remember { mutableStateOf(true) }

    var myLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var myBearing by remember { mutableStateOf(0f) }
    var destination by remember { mutableStateOf<GeoPoint?>(null) }
    var routeDistanceM by remember { mutableStateOf(0f) }
    var bearingToDest by remember { mutableStateOf(0f) }
    var turnHint by remember { mutableStateOf("—") }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = FloatArray(3)
        val magnet = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accel, 0, 3)
                    Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnet, 0, 3)
                }
                val R = FloatArray(9)
                val I = FloatArray(9)
                if (SensorManager.getRotationMatrix(R, I, accel, magnet)) {
                    val orient = FloatArray(3)
                    SensorManager.getOrientation(R, orient)
                    val deg = Math.toDegrees(orient[0].toDouble()).toFloat()
                    myBearing = ((deg + 360f) % 360f)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sm.unregisterListener(listener) }
    }

    DisposableEffect(hasLocation) {
        if (!hasLocation) return@DisposableEffect onDispose {}
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(lr: LocationResult) {
                val loc = lr.lastLocation ?: return
                myLocation = GeoPoint(loc.latitude, loc.longitude)
                destination?.let { dest ->
                    val results = FloatArray(3)
                    Location.distanceBetween(
                        loc.latitude, loc.longitude,
                        dest.lat, dest.lon,
                        results
                    )
                    routeDistanceM = results[0]
                    bearingToDest = results[1]
                    val heading = if (loc.hasBearing() && loc.speed > 0.8f) loc.bearing else myBearing
                    val delta = ((bearingToDest - heading + 540f) % 360f) - 180f
                    turnHint = when {
                        abs(delta) < 20f -> "ПРЯМО"
                        delta > 0 -> "ВПРАВО ${delta.toInt()}°"
                        else -> "ВЛЕВО ${(-delta).toInt()}°"
                    }
                }
            }
        }
        try {
            // Permission already checked via hasLocation / runtime grants
            client.requestLocationUpdates(request, callback, context.mainLooper)
        } catch (e: SecurityException) {
            Log.e("Diplomka", "Location permission missing", e)
        }
        onDispose { client.removeLocationUpdates(callback) }
    }

    val borderColor = if (result?.stopSignal == true) Color(0xFFDA3633) else Color(0xFF238636)

    Box(Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (showLog) 0.58f else 1f)
                .border(3.dp, borderColor)
        ) {
            CameraPreviewWithAnalysis(
                lifecycleOwner = lifecycleOwner,
                isRunning = isRunning,
                analysisExecutor = analysisExecutor,
                onFrame = { bitmap ->
                    if (!isRunning) return@CameraPreviewWithAnalysis
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate.get() < 200) return@CameraPreviewWithAnalysis
                    lastUiUpdate.set(now)
                    result = pipeline.process(bitmap)
                }
            )

            result?.let { r ->
                DetectionOverlay(
                    detections = r.detections,
                    targetX = r.targetX,
                    targetY = r.targetY,
                    stopSignal = r.stopSignal,
                    autoRotateAngle = if (autoRotate) {
                        ((bearingToDest - myBearing + 360f) % 360f)
                    } else 0f,
                    showCompass = destination != null,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(Color(0xCC0D1117), RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text("DIPLOMKA", color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                result?.let { r ->
                    Text(
                        "CV: " + String.format("%.1f", r.estimatedSpeedMps) + " м/с | " +
                            String.format("%.0f", r.totalDistanceM) + " м | " + r.weather,
                        color = Color(0xFFE6EDF3),
                        fontSize = 11.sp
                    )
                }
                myLocation?.let { loc ->
                    Text(
                        "GPS: " + String.format("%.5f", loc.lat) + ", " + String.format("%.5f", loc.lon),
                        color = Color(0xFF8B949E),
                        fontSize = 10.sp
                    )
                }
                if (destination != null) {
                    Text(
                        "До цели: " + String.format("%.0f", routeDistanceM) + " м | $turnHint",
                        color = Color(0xFF7EE787),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
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
                    Text("СТОП!", color = Color(0xFFFF6B6B), fontSize = 32.sp, fontWeight = FontWeight.Black)
                }
            }

            Column(
                Modifier.align(Alignment.BottomEnd).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.End
            ) {
                FilledTonalButton(
                    onClick = {
                        myLocation?.let { loc ->
                            val dist = 300.0
                            val brng = Math.toRadians(myBearing.toDouble())
                            val R = 6371000.0
                            val lat1 = Math.toRadians(loc.lat)
                            val lon1 = Math.toRadians(loc.lon)
                            val lat2 = asin(sin(lat1) * cos(dist / R) + cos(lat1) * sin(dist / R) * cos(brng))
                            val lon2 = lon1 + atan2(
                                sin(brng) * sin(dist / R) * cos(lat1),
                                cos(dist / R) - sin(lat1) * sin(lat2)
                            )
                            destination = GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF21262D))
                ) {
                    Icon(Icons.Default.Flag, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Цель 300м", color = Color.White, fontSize = 12.sp)
                }
                FilledTonalButton(
                    onClick = { autoRotate = !autoRotate },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (autoRotate) Color(0xFF238636) else Color(0xFF21262D)
                    )
                ) {
                    Icon(Icons.Default.Explore, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (autoRotate) "Авто-поворот" else "Компас выкл", color = Color.White, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = { showLog = !showLog },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF21262D))
                    ) {
                        Icon(
                            if (showLog) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            null, tint = Color.White, modifier = Modifier.size(18.dp)
                        )
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
                            null, tint = Color.White, modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        if (showLog) {
            CommandLogPanel(
                commands = result?.commands ?: emptyList(),
                turnHint = if (destination != null) turnHint else null,
                routeDistanceM = if (destination != null) routeDistanceM else null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.42f)
            )
        }
    }
}

@Composable
fun CommandLogPanel(
    commands: List<DroneVisionPipeline.NavigationCommand>,
    turnHint: String?,
    routeDistanceM: Float?,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val firstTs = commands.firstOrNull()?.timestamp

    LaunchedEffect(firstTs) {
        if (commands.isNotEmpty()) {
            try { listState.animateScrollToItem(0) } catch (_: Exception) {}
        }
    }

    Column(
        modifier.background(Color(0xFF161B22)).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Terminal, null, tint = Color(0xFF58A6FF), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Команды (реальное время)", color = Color(0xFF58A6FF), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        if (turnHint != null && routeDistanceM != null) {
            Text(
                "Навигация GPS: $turnHint · " + String.format("%.0f", routeDistanceM) + " м до цели",
                color = Color(0xFF7EE787),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Divider(color = Color(0xFF30363D))
        Spacer(Modifier.height(4.dp))

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(commands, key = { it.timestamp.toString() + it.text }) { cmd ->
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
    autoRotateAngle: Float,
    showCompass: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        for (d in detections) {
            val color = when (d.type) {
                "person" -> Color(0xFFFF4444)
                "vehicle" -> Color(0xFF4488FF)
                else -> Color.White
            }
            val left = d.rect.left.coerceIn(0f, w)
            val top = d.rect.top.coerceIn(0f, h)
            val right = d.rect.right.coerceIn(0f, w)
            val bottom = d.rect.bottom.coerceIn(0f, h)
            if (right - left < 4 || bottom - top < 4) continue
            drawRect(color = color.copy(alpha = 0.2f), topLeft = Offset(left, top), size = Size(right - left, bottom - top))
            drawRect(color = color, topLeft = Offset(left, top), size = Size(right - left, bottom - top), style = Stroke(width = 2.5f))
        }

        val tx = targetX * w
        val ty = targetY * h
        drawCircle(Color(0xFF00FF88), radius = 10f, center = Offset(tx, ty))
        drawLine(Color(0xFF00FF88), Offset(w / 2f, h * 0.92f), Offset(tx, ty), strokeWidth = 3f)

        if (showCompass) {
            val cx = w / 2f
            val cy = h * 0.78f
            rotate(degrees = autoRotateAngle, pivot = Offset(cx, cy)) {
                val path = Path().apply {
                    moveTo(cx, cy - 40f)
                    lineTo(cx - 18f, cy + 22f)
                    lineTo(cx, cy + 8f)
                    lineTo(cx + 18f, cy + 22f)
                    close()
                }
                drawPath(path, Color(0xFFFFD700))
            }
            drawCircle(Color(0x66FFD700), radius = 48f, center = Offset(cx, cy), style = Stroke(2f))
        }

        if (stopSignal) {
            drawRect(Color(0x33FF0000), Offset.Zero, Size(w, h))
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

    val runningState = rememberUpdatedState(isRunning)
    val onFrameState = rememberUpdatedState(onFrame)

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize()) { view ->
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
                if (!runningState.value) {
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
                    onFrameState.value(finalBitmap)
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
