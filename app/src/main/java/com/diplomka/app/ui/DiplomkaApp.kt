package com.diplomka.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diplomka.app.vision.DroneVisionPipeline
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

data class GeoPoint(val lat: Double, val lon: Double)
enum class SourceMode { CAMERA, VIDEO }

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiplomkaApp() {
    val permissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )
    LaunchedEffect(Unit) {
        if (!permissions.allPermissionsGranted) permissions.launchMultiplePermissionRequest()
    }
    val hasCamera = permissions.permissions.any { it.status.isGranted && it.permission == Manifest.permission.CAMERA }
    val hasLocation = permissions.permissions.any {
        it.status.isGranted && (it.permission == Manifest.permission.ACCESS_FINE_LOCATION || it.permission == Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    DroneNavigationScreen(hasLocation = hasLocation, hasCamera = hasCamera)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DroneNavigationScreen(hasLocation: Boolean, hasCamera: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val pipeline = remember { DroneVisionPipeline() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val lastUiUpdate = remember { AtomicLong(0L) }
    val cameraBound = remember { AtomicBoolean(false) }

    var result by remember { mutableStateOf<DroneVisionPipeline.FrameResult?>(null) }
    var showLog by remember { mutableStateOf(true) }
    var isRunning by remember { mutableStateOf(true) }
    var autoRotate by remember { mutableStateOf(true) }
    var strictStop by remember { mutableStateOf(true) }
    var forceStop by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var cameraOptions by remember { mutableStateOf<List<CameraOption>>(emptyList()) }
    var cameraIndex by remember { mutableStateOf(0) }
    var cameraControlRef by remember { mutableStateOf<CameraControl?>(null) }
    var sourceMode by remember { mutableStateOf(SourceMode.CAMERA) }
    var showMap by remember { mutableStateOf(false) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var videoPlaying by remember { mutableStateOf(false) }
    var videoFrame by remember { mutableStateOf<Bitmap?>(null) }
    var offlineMapUri by remember { mutableStateOf<Uri?>(null) }
    var screenWaypoint by remember { mutableStateOf<Offset?>(null) }
    var myLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var myBearing by remember { mutableStateOf(0f) }
    var destination by remember { mutableStateOf<GeoPoint?>(null) }
    var routeDistanceM by remember { mutableStateOf(0f) }
    var bearingToDest by remember { mutableStateOf(0f) }
    var turnHint by remember { mutableStateOf("—") }

    LaunchedEffect(strictStop) { pipeline.strictStopEnabled = strictStop }
    LaunchedEffect(forceStop) { pipeline.forceStop = forceStop }
    LaunchedEffect(torchOn, cameraControlRef) {
        try { cameraControlRef?.enableTorch(torchOn) } catch (_: Exception) {}
    }
    LaunchedEffect(Unit) {
        cameraOptions = enumerateCameras(context)
        cameraIndex = 0
        Log.i("Diplomka", "Cameras: " + cameraOptions.joinToString { it.label })
    }

    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = FloatArray(3); val magnet = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, accel, 0, 3)
                    Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, magnet, 0, 3)
                }
                val R = FloatArray(9); val I = FloatArray(9)
                if (SensorManager.getRotationMatrix(R, I, accel, magnet)) {
                    val orient = FloatArray(3)
                    SensorManager.getOrientation(R, orient)
                    myBearing = ((Math.toDegrees(orient[0].toDouble()).toFloat() + 360f) % 360f)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(listener) }
    }

    DisposableEffect(hasLocation) {
        if (!hasLocation) return@DisposableEffect onDispose {}
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).setMinUpdateIntervalMillis(1000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(lr: LocationResult) {
                val loc = lr.lastLocation ?: return
                myLocation = GeoPoint(loc.latitude, loc.longitude)
                destination?.let { dest ->
                    val results = FloatArray(3)
                    Location.distanceBetween(loc.latitude, loc.longitude, dest.lat, dest.lon, results)
                    routeDistanceM = results[0]; bearingToDest = results[1]
                    val heading = if (loc.hasBearing() && loc.speed > 0.5f) loc.bearing else myBearing
                    val delta = ((bearingToDest - heading + 540f) % 360f) - 180f
                    turnHint = when {
                        abs(delta) < 18f -> "ПРЯМО"
                        delta > 0 -> "ВПРАВО ${delta.toInt()}°"
                        else -> "ВЛЕВО ${(-delta).toInt()}°"
                    }
                }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, context.mainLooper)
            client.lastLocation.addOnSuccessListener { loc -> if (loc != null) myLocation = GeoPoint(loc.latitude, loc.longitude) }
        } catch (e: SecurityException) { Log.e("Diplomka", "Location", e) }
        onDispose { client.removeLocationUpdates(callback) }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            videoUri = uri; sourceMode = SourceMode.VIDEO; videoPlaying = true
            pipeline.reset(); result = null; videoFrame = null
        }
    }
    val mapFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { offlineMapUri = uri; showMap = true }
    }

    LaunchedEffect(videoUri, videoPlaying, sourceMode) {
        if (sourceMode != SourceMode.VIDEO || videoUri == null || !videoPlaying) return@LaunchedEffect
        val uri = videoUri!!
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var t = 0L
                while (isActive && videoPlaying && sourceMode == SourceMode.VIDEO) {
                    val frame = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) {
                        val r = pipeline.process(frame)
                        val display = frame.copy(Bitmap.Config.ARGB_8888, false)
                        withContext(Dispatchers.Main) { result = r; videoFrame?.recycle(); videoFrame = display }
                        frame.recycle()
                    }
                    t += 120L
                    if (durationMs > 0 && t > durationMs) t = 0L
                    delay(120)
                }
            } catch (e: Exception) { Log.e("Diplomka", "Video", e) }
            finally { try { retriever.release() } catch (_: Exception) {} }
        }
    }

    val borderColor = if (result?.stopSignal == true || forceStop) Color(0xFFDA3633) else Color(0xFF238636)
    val selectedOption = cameraOptions.getOrNull(cameraIndex)
    val selectedCameraId = selectedOption?.id
    val selectedPhysicalId = selectedOption?.physicalId
    val selectedZoom = selectedOption?.zoomRatio

    Box(Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (showLog) 0.58f else 1f)
                .border(3.dp, borderColor)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val nx = (offset.x / size.width).coerceIn(0.05f, 0.95f)
                        val ny = (offset.y / size.height).coerceIn(0.05f, 0.95f)
                        screenWaypoint = Offset(nx, ny)
                        val base = myLocation ?: GeoPoint(55.751244, 37.618423)
                        if (myLocation == null) myLocation = base
                        val angleOff = (nx - 0.5f) * 60f
                        destination = pointAhead(base, myBearing + angleOff, 150.0)
                    }
                }
        ) {
            when {
                sourceMode == SourceMode.CAMERA && hasCamera -> {
                    StableCameraPreview(
                        lifecycleOwner = lifecycleOwner,
                        isRunning = isRunning,
                        analysisExecutor = analysisExecutor,
                        cameraBound = cameraBound,
                        cameraId = selectedCameraId,
                        physicalId = selectedPhysicalId,
                        zoomRatio = selectedZoom,
                        torchOn = torchOn,
                        onCameraControl = { cameraControlRef = it },
                        onFrame = { bitmap ->
                            if (!isRunning) return@StableCameraPreview
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate.get() < 200) return@StableCameraPreview
                            lastUiUpdate.set(now)
                            result = pipeline.process(bitmap)
                        }
                    )
                }
                sourceMode == SourceMode.VIDEO -> {
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                        val vf = videoFrame
                        if (vf != null) Image(bitmap = vf.asImageBitmap(), contentDescription = "video", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(if (videoPlaying) "Анализ видео…" else "Видео на паузе", color = Color.White)
                    }
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Нет камеры — выберите видео", color = Color.White)
                }
            }

            result?.let { r ->
                DetectionOverlay(
                    detections = r.detections,
                    targetX = r.targetX,
                    targetY = r.targetY,
                    stopSignal = r.stopSignal,
                    nnPoint = r.nnNextPoint,
                    autoRotateAngle = if (autoRotate && destination != null) ((bearingToDest - myBearing + 360f) % 360f) else myBearing,
                    showCompass = true,
                    screenWaypoint = screenWaypoint,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (result?.stopSignal == true || forceStop) {
                Box(
                    Modifier.align(Alignment.Center)
                        .background(Color(0xAA000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text(if (forceStop) "СТОП (ручной)" else "СТОП!", color = Color(0xFFFF6B6B), fontSize = 32.sp, fontWeight = FontWeight.Black)
                }
            }

            result?.let { r ->
                if (r.nearestDistanceM > 0) {
                    Text(
                        "≈ " + String.format("%.1f", r.nearestDistanceM) + " м",
                        color = Color(0xFFFFD700),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconBtn(
                    icon = if (menuExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    active = menuExpanded,
                    tint = Color(0xFF58A6FF)
                ) { menuExpanded = !menuExpanded }

                AnimatedVisibility(
                    visible = menuExpanded,
                    enter = expandVertically(animationSpec = tween(220)) + fadeIn(),
                    exit = shrinkVertically(animationSpec = tween(180)) + fadeOut()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconBtn(Icons.Default.Stop, forceStop, Color(0xFFFF6B6B)) { forceStop = !forceStop }
                        IconBtn(if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow, !isRunning, Color(0xFF58A6FF)) { isRunning = !isRunning }
                        IconBtn(if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff, torchOn, Color(0xFFFFD740)) { torchOn = !torchOn }
                        IconBtn(Icons.Default.Cameraswitch, false, Color(0xFF79C0FF)) {
                            if (cameraOptions.isNotEmpty()) cameraIndex = (cameraIndex + 1) % cameraOptions.size
                        }
                        IconBtn(Icons.Default.Videocam, sourceMode == SourceMode.VIDEO, Color(0xFFD2A8FF)) { videoPicker.launch("video/*") }
                        IconBtn(Icons.Default.Map, showMap, Color(0xFF7EE787)) { showMap = !showMap }
                        IconBtn(Icons.Default.Folder, offlineMapUri != null, Color(0xFF8B949E)) { mapFilePicker.launch("*/*") }
                        IconBtn(Icons.Default.ScreenRotation, autoRotate, Color(0xFF79C0FF)) {
                            autoRotate = !autoRotate
                            val act = context as? Activity
                            act?.requestedOrientation = if (autoRotate) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        }
                        IconBtn(Icons.Default.Warning, strictStop, Color(0xFFD29922)) { strictStop = !strictStop }
                        IconBtn(if (showLog) Icons.Default.ExpandMore else Icons.Default.ExpandLess, showLog, Color(0xFF8B949E)) { showLog = !showLog }
                    }
                }
            }
        }

        if (showMap) {
            OfflineNavMap(
                myLocation = myLocation,
                destination = destination,
                bearing = myBearing,
                freeSector = result?.freeSectorDeg ?: 0f,
                nearestM = result?.nearestDistanceM ?: -1f,
                offlineFile = offlineMapUri,
                modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(0.42f).fillMaxHeight(0.35f).padding(8.dp)
            )
        }

        if (showLog) {
            CommandLogPanel(
                commands = result?.commands ?: emptyList(),
                turnHint = turnHint,
                routeDistanceM = routeDistanceM.takeIf { destination != null },
                cameraLabel = selectedOption?.label,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.42f)
            )
        }
    }
}

@Composable
private fun IconBtn(icon: ImageVector, active: Boolean, tint: Color, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .background(if (active) Color(0xFF21262D) else Color(0xCC161B22), CircleShape)
            .border(1.dp, if (active) tint else Color(0xFF30363D), CircleShape)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
