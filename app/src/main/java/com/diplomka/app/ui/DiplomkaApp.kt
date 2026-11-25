package com.diplomka.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiplomkaApp() {
    val permissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )
    LaunchedEffect(Unit) { if (!permissions.allPermissionsGranted) permissions.launchMultiplePermissionRequest() }
    val hasCamera = permissions.permissions.any { it.status.isGranted && it.permission == Manifest.permission.CAMERA }
    val hasLocation = permissions.permissions.any {
        it.status.isGranted && (it.permission == Manifest.permission.ACCESS_FINE_LOCATION || it.permission == Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    DroneNavigationScreen(hasLocation = hasLocation, hasCamera = hasCamera)
}

data class GeoPoint(val lat: Double, val lon: Double)
data class CameraOption(val id: String, val label: String, val facing: Int)
enum class SourceMode { CAMERA, VIDEO }

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
    var mapOffline by remember { mutableStateOf(true) }
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
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val opts = mutableListOf<CameraOption>()
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                if (facing == CameraCharacteristics.LENS_FACING_FRONT) continue
                val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val minF = focals?.minOrNull() ?: 4f
                val label = when {
                    facing != CameraCharacteristics.LENS_FACING_BACK && facing != CameraCharacteristics.LENS_FACING_FRONT -> "USB $id"
                    minF < 2.8f -> "Ширик $id"
                    minF > 5.5f -> "Теле $id"
                    else -> "Осн $id"
                }
                opts.add(CameraOption(id, label, facing))
            }
            if (opts.isEmpty()) opts.add(CameraOption("0", "Основная", CameraCharacteristics.LENS_FACING_BACK))
            cameraOptions = opts
            cameraIndex = 0
        } catch (e: Exception) {
            Log.e("Diplomka", "lenses", e)
            cameraOptions = listOf(CameraOption("0", "Основная", CameraCharacteristics.LENS_FACING_BACK))
        }
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
        if (uri != null) { offlineMapUri = uri; mapOffline = true; showMap = true }
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
    val selectedCameraId = cameraOptions.getOrNull(cameraIndex)?.id

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
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconBtn(Icons.Default.SwapHoriz, sourceMode == SourceMode.VIDEO) {
                            if (sourceMode == SourceMode.CAMERA) videoPicker.launch("video/*")
                            else {
                                sourceMode = SourceMode.CAMERA
                                videoPlaying = false
                                cameraBound.set(false)
                                pipeline.reset()
                            }
                        }
                        IconBtn(Icons.Default.Map, showMap) { showMap = true }
                        IconBtn(
                            if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            torchOn,
                            if (torchOn) Color(0xFFFFC107) else Color.White
                        ) {
                            torchOn = !torchOn
                            try { cameraControlRef?.enableTorch(torchOn) } catch (_: Exception) {}
                        }
                        IconBtn(Icons.Default.Cameraswitch) {
                            if (cameraOptions.isNotEmpty()) {
                                cameraIndex = (cameraIndex + 1) % cameraOptions.size
                                cameraBound.set(false)
                                torchOn = false
                                Log.i("Diplomka", "Switch camera -> ${cameraOptions[cameraIndex].id}")
                            }
                        }
                        IconBtn(Icons.Default.ScreenRotation) {
                            val act = context as? Activity
                            act?.let {
                                val cur = it.requestedOrientation
                                it.requestedOrientation = if (
                                    cur == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
                                    cur == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                ) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            }
                        }
                        IconBtn(Icons.Default.Flag) {
                            val base = myLocation ?: GeoPoint(55.751244, 37.618423)
                            if (myLocation == null) myLocation = base
                            destination = pointAhead(base, myBearing, 300.0)
                            screenWaypoint = Offset(0.5f, 0.35f)
                        }
                        IconBtn(Icons.Default.Explore, autoRotate) { autoRotate = !autoRotate }
                        IconBtn(Icons.Default.Stop, forceStop, if (forceStop) Color(0xFFFF6B6B) else Color.White) {
                            forceStop = !forceStop
                            pipeline.forceStop = forceStop
                        }
                        IconBtn(Icons.Default.Warning, strictStop) { strictStop = !strictStop }
                        IconBtn(Icons.Default.Terminal, showLog) { showLog = !showLog }
                        IconBtn(
                            if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            isRunning,
                            if (isRunning) Color(0xFFFF6B6B) else Color(0xFF7EE787)
                        ) {
                            isRunning = !isRunning
                            videoPlaying = isRunning && sourceMode == SourceMode.VIDEO
                            if (!isRunning) pipeline.reset()
                        }
                    }
                }
            }
        }

        if (showLog) {
            CommandLogPanel(
                result?.commands ?: emptyList(),
                if (destination != null) turnHint else null,
                if (destination != null) routeDistanceM else null,
                cameraOptions.getOrNull(cameraIndex)?.label,
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.42f)
            )
        }
    }

    if (showMap) {
        ModalBottomSheet(onDismissRequest = { showMap = false }) {
            Column(Modifier.fillMaxWidth().height(460.dp).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Карта", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { mapOffline = !mapOffline }) { Text(if (mapOffline) "Оффлайн" else "Онлайн") }
                    TextButton(onClick = { mapFilePicker.launch("*/*") }) { Text("Файл") }
                }
                Spacer(Modifier.height(6.dp))
                if (mapOffline) {
                    OfflineNavMap(myLocation, destination, myBearing, result?.freeSectorDeg ?: 0f, result?.nearestDistanceM ?: -1f, offlineMapUri, Modifier.fillMaxSize())
                } else {
                    val lat = myLocation?.lat ?: 55.751244
                    val lon = myLocation?.lon ?: 37.618423
                    val dlat = destination?.lat ?: lat
                    val dlon = destination?.lon ?: lon
                    AndroidView(factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            webViewClient = WebViewClient()
                            loadUrl("https://yandex.ru/maps/?ll=$lon%2C$lat&z=15&rtext=${lat}%2C${lon}~${dlat}%2C${dlon}&rtt=auto")
                        }
                    }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun IconBtn(icon: ImageVector, active: Boolean = false, tint: Color = Color.White, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) Color(0xFF30363D) else Color(0xCC21262D)
        )
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun OfflineNavMap(
    myLocation: GeoPoint?, destination: GeoPoint?, bearing: Float,
    freeSector: Float, nearestM: Float, offlineFile: Uri?, modifier: Modifier = Modifier
) {
    Column(modifier.background(Color(0xFF0D1117))) {
        Text(
            if (offlineFile != null) "Оффлайн файл: ${offlineFile.lastPathSegment}"
            else "Оффлайн схема. Тап по камере ставит точку. Загрузите карту «Файл».",
            color = Color(0xFF8B949E), fontSize = 11.sp, modifier = Modifier.padding(4.dp)
        )
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val w = size.width; val h = size.height; val cx = w / 2f; val cy = h * 0.65f
            val grid = Color(0xFF21262D)
            for (i in 0..8) {
                drawLine(grid, Offset(w * i / 8f, 0f), Offset(w * i / 8f, h), 1f)
                drawLine(grid, Offset(0f, h * i / 8f), Offset(w, h * i / 8f), 1f)
            }
            rotate(degrees = freeSector, pivot = Offset(cx, cy)) {
                val path = Path().apply {
                    moveTo(cx, cy); lineTo(cx - w * 0.12f, cy - h * 0.45f); lineTo(cx + w * 0.12f, cy - h * 0.45f); close()
                }
                drawPath(path, Color(0x5548D597))
            }
            if (destination != null && myLocation != null) {
                val results = FloatArray(3)
                Location.distanceBetween(myLocation.lat, myLocation.lon, destination.lat, destination.lon, results)
                val dist = results[0]; val brg = results[1]
                val rel = ((brg - bearing + 540f) % 360f) - 180f
                val scale = min(w, h) * 0.35f / max(dist, 50f)
                val dx = sin(Math.toRadians(rel.toDouble())).toFloat() * dist * scale
                val dy = -cos(Math.toRadians(rel.toDouble())).toFloat() * dist * scale
                drawCircle(Color(0xFFFF7B72), 10f, Offset(cx + dx, cy + dy))
                drawLine(Color(0x88FF7B72), Offset(cx, cy), Offset(cx + dx, cy + dy), 2f)
            }
            val arrow = Path().apply {
                moveTo(cx, cy - 28f); lineTo(cx - 12f, cy + 14f); lineTo(cx, cy + 4f); lineTo(cx + 12f, cy + 14f); close()
            }
            drawPath(arrow, Color(0xFF58A6FF))
            drawCircle(Color(0xFF58A6FF), 6f, Offset(cx, cy))
            if (nearestM > 0) {
                val r = (nearestM / 8f).coerceIn(0.05f, 0.4f) * min(w, h)
                drawCircle(Color(0x66FFAA33), r, Offset(cx, cy), style = Stroke(3f))
            }
        }
    }
}

private fun pointAhead(from: GeoPoint, bearingDeg: Float, distM: Double): GeoPoint {
    val brng = Math.toRadians(bearingDeg.toDouble()); val R = 6371000.0
    val lat1 = Math.toRadians(from.lat); val lon1 = Math.toRadians(from.lon)
    val lat2 = asin(sin(lat1) * cos(distM / R) + cos(lat1) * sin(distM / R) * cos(brng))
    val lon2 = lon1 + atan2(sin(brng) * sin(distM / R) * cos(lat1), cos(distM / R) - sin(lat1) * sin(lat2))
    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

@Composable
fun CommandLogPanel(
    commands: List<DroneVisionPipeline.NavigationCommand>,
    turnHint: String?, routeDistanceM: Float?, cameraLabel: String?, modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val firstTs = commands.firstOrNull()?.timestamp
    LaunchedEffect(firstTs) { if (commands.isNotEmpty()) try { listState.animateScrollToItem(0) } catch (_: Exception) {} }
    Column(modifier.background(Color(0xFF161B22)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Terminal, null, tint = Color(0xFF58A6FF), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Команды", color = Color(0xFF58A6FF), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (cameraLabel != null) Text(cameraLabel, color = Color(0xFF8B949E), fontSize = 10.sp)
        }
        if (turnHint != null && routeDistanceM != null) {
            Text("GPS: $turnHint · " + String.format("%.0f", routeDistanceM) + " м", color = Color(0xFF7EE787), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Divider(color = Color(0xFF30363D), modifier = Modifier.padding(vertical = 4.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(commands, key = { it.timestamp.toString() + it.text }) { cmd ->
                val color = when (cmd.severity) {
                    DroneVisionPipeline.NavigationCommand.Severity.STOP -> Color(0xFFFF7B72)
                    DroneVisionPipeline.NavigationCommand.Severity.WARN -> Color(0xFFD29922)
                    DroneVisionPipeline.NavigationCommand.Severity.NAV -> Color(0xFF7EE787)
                    else -> Color(0xFF8B949E)
                }
                Text("› ${cmd.text}", color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

@Composable
fun DetectionOverlay(
    detections: List<DroneVisionPipeline.Detection>,
    targetX: Float, targetY: Float, stopSignal: Boolean,
    nnPoint: Pair<Float, Float>,
    autoRotateAngle: Float, showCompass: Boolean,
    screenWaypoint: Offset?,
    modifier: Modifier = Modifier
) {
    val infinite = rememberInfiniteTransition(label = "arrow")
    val pulse by infinite.animateFloat(
        initialValue = 0.92f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val bob by infinite.animateFloat(
        initialValue = -4f, targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "bob"
    )

    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height
        val textPaint = android.graphics.Paint().apply { textSize = 30f; isAntiAlias = true; isFakeBoldText = true }
        for (d in detections) {
            val color = when (d.type) {
                "person" -> Color(0xFF4FC3F7)
                "car" -> Color(0xFFFF7043)
                "building" -> Color(0xFF81C784)
                "motion" -> Color(0xFFE040FB)
                else -> Color(0xFFFFCA28)
            }
            val left = d.rect.left.coerceIn(0f, w)
            val top = d.rect.top.coerceIn(0f, h)
            val right = d.rect.right.coerceIn(0f, w)
            val bottom = d.rect.bottom.coerceIn(0f, h)
            if (right - left < 6 || bottom - top < 6) continue
            drawRect(color.copy(alpha = 0.25f), Offset(left, top), Size(right - left, bottom - top))
            drawRect(color, Offset(left, top), Size(right - left, bottom - top), style = Stroke(4.5f))
            val cLen = minOf(22f, (right - left) / 2.5f, (bottom - top) / 2.5f)
            val sw = 5f
            drawLine(color, Offset(left, top), Offset(left + cLen, top), sw)
            drawLine(color, Offset(left, top), Offset(left, top + cLen), sw)
            drawLine(color, Offset(right, top), Offset(right - cLen, top), sw)
            drawLine(color, Offset(right, top), Offset(right, top + cLen), sw)
            drawLine(color, Offset(left, bottom), Offset(left + cLen, bottom), sw)
            drawLine(color, Offset(left, bottom), Offset(left, bottom - cLen), sw)
            drawLine(color, Offset(right, bottom), Offset(right - cLen, bottom), sw)
            drawLine(color, Offset(right, bottom), Offset(right, bottom - cLen), sw)
            val label = buildString {
                append(d.type)
                if (d.distanceM > 0) append(" ").append(String.format("%.1f", d.distanceM)).append("м")
            }
            val ty = (top - 8f).coerceAtLeast(28f)
            textPaint.color = android.graphics.Color.BLACK
            drawContext.canvas.nativeCanvas.drawText(label, left + 3f, ty, textPaint)
            textPaint.color = android.graphics.Color.WHITE
            drawContext.canvas.nativeCanvas.drawText(label, left + 2f, ty - 1f, textPaint)
        }
        val nx = nnPoint.first * w; val ny = nnPoint.second * h
        drawCircle(Color(0xFF69F0AE), 10f, Offset(nx, ny))
        drawLine(Color(0xFF69F0AE), Offset(w / 2f, h * 0.9f), Offset(nx, ny), 3f)
        drawCircle(Color(0xFF00E676), 7f, Offset(targetX * w, targetY * h))

        screenWaypoint?.let { wp ->
            val px = wp.x * w; val py = wp.y * h
            drawCircle(Color(0xFFFF5252), 14f, Offset(px, py))
            drawCircle(Color(0x88FF5252), 28f, Offset(px, py), style = Stroke(3f))
            drawLine(Color(0xAAFF5252), Offset(w / 2f, h), Offset(px, py), 2.5f)
        }

        if (showCompass) {
            val cx = w / 2f
            val cy = h * 0.78f + bob
            rotate(degrees = autoRotateAngle, pivot = Offset(cx, cy)) {
                val s = 1.35f * pulse
                val shadow = Path().apply {
                    moveTo(cx + 3f, cy - 52f * s + 4f)
                    lineTo(cx - 20f * s + 3f, cy + 26f * s + 4f)
                    lineTo(cx + 3f, cy + 8f * s + 4f)
                    lineTo(cx + 20f * s + 3f, cy + 26f * s + 4f)
                    close()
                }
                drawPath(shadow, Color(0x66000000))
                val body = Path().apply {
                    moveTo(cx, cy - 52f * s)
                    lineTo(cx - 20f * s, cy + 26f * s)
                    lineTo(cx, cy + 8f * s)
                    lineTo(cx + 20f * s, cy + 26f * s)
                    close()
                }
                drawPath(body, Color(0xFFFFD740))
                val highlight = Path().apply {
                    moveTo(cx, cy - 52f * s)
                    lineTo(cx - 8f * s, cy + 2f * s)
                    lineTo(cx, cy + 8f * s)
                    close()
                }
                drawPath(highlight, Color(0xFFFFF59D))
            }
            drawCircle(Color(0x55FFD740), 48f * pulse, Offset(cx, cy), style = Stroke(2f))
        }
        if (stopSignal) drawRect(Color(0x44FF0000), Offset.Zero, Size(w, h))
    }
}

@Composable
fun StableCameraPreview(
    lifecycleOwner: LifecycleOwner,
    isRunning: Boolean,
    analysisExecutor: java.util.concurrent.Executor,
    cameraBound: AtomicBoolean,
    cameraId: String?,
    torchOn: Boolean = false,
    onCameraControl: (CameraControl?) -> Unit = {},
    onFrame: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }
    val runningState = rememberUpdatedState(isRunning)
    val onFrameState = rememberUpdatedState(onFrame)
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    DisposableEffect(lifecycleOwner, cameraId) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        val listener = Runnable {
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9).build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    if (!runningState.value) { imageProxy.close(); return@setAnalyzer }
                    try {
                        val bitmap = imageProxy.toBitmap()
                        val rotation = imageProxy.imageInfo.rotationDegrees
                        val finalBitmap = if (rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        } else bitmap
                        onFrameState.value(finalBitmap)
                    } catch (e: Exception) { Log.e("Diplomka", "frame", e) }
                    finally { imageProxy.close() }
                }
                cameraProvider.unbindAll()
                val selector = buildCameraSelectorById(cameraId)
                val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                onCameraControl(camera.cameraControl)
                try { camera.cameraControl.enableTorch(torchOn) } catch (_: Exception) {}
                cameraBound.set(true)
                Log.i("Diplomka", "Bound camera id=$cameraId")
            } catch (e: Exception) {
                Log.e("Diplomka", "bind id=$cameraId", e)
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                    val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_16_9).build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        if (!runningState.value) { imageProxy.close(); return@setAnalyzer }
                        try {
                            val bitmap = imageProxy.toBitmap()
                            onFrameState.value(bitmap)
                        } catch (_: Exception) {}
                        finally { imageProxy.close() }
                    }
                    val camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    onCameraControl(camera.cameraControl)
                    cameraBound.set(true)
                } catch (e2: Exception) { Log.e("Diplomka", "fallback bind", e2) }
            }
        }
        cameraProviderFuture.addListener(listener, executor)
        onDispose {
            try { cameraProviderFuture.get().unbindAll() } catch (_: Exception) {}
            onCameraControl(null)
            cameraBound.set(false)
        }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
private fun buildCameraSelectorById(cameraId: String?): CameraSelector {
    if (cameraId.isNullOrBlank()) return CameraSelector.DEFAULT_BACK_CAMERA
    return CameraSelector.Builder()
        .addCameraFilter { cameraInfos ->
            val match = cameraInfos.filter { info ->
                try { Camera2CameraInfo.from(info).cameraId == cameraId } catch (_: Exception) { false }
            }
            if (match.isNotEmpty()) match
            else cameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        }
        .build()
}

fun ImageProxy.toBitmap(): Bitmap {
    val buffer = planes[0].buffer; buffer.rewind()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.copyPixelsFromBuffer(buffer)
    return bitmap
}
