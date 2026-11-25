package com.diplomka.app.ui

import android.location.Location
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.diplomka.app.vision.DroneVisionPipeline
import kotlin.math.*

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
            drawCircle(Color(0xFF58A6FF), 10f, Offset(cx, cy))
            if (nearestM > 0) {
                drawCircle(Color(0x44FF6B6B), (nearestM.coerceIn(0.5f, 8f) / 8f) * w * 0.25f, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            }
        }
        if (offlineFile != null) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        webViewClient = WebViewClient()
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

fun pointAhead(from: GeoPoint, bearingDeg: Float, distM: Double): GeoPoint {
    val R = 6371000.0
    val brng = Math.toRadians(bearingDeg.toDouble())
    val lat1 = Math.toRadians(from.lat)
    val lon1 = Math.toRadians(from.lon)
    val lat2 = asin(sin(lat1) * cos(distM / R) + cos(lat1) * sin(distM / R) * cos(brng))
    val lon2 = lon1 + atan2(sin(brng) * sin(distM / R) * cos(lat1), cos(distM / R) - sin(lat1) * sin(lat2))
    return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

@Composable
fun CommandLogPanel(
    commands: List<DroneVisionPipeline.NavigationCommand>,
    turnHint: String?,
    routeDistanceM: Float?,
    cameraLabel: String?,
    modifier: Modifier = Modifier
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
            if (cameraLabel != null) {
                Text(cameraLabel, color = Color(0xFF8B949E), fontSize = 10.sp)
            }
        }
        if (turnHint != null && routeDistanceM != null) {
            Text(
                "GPS: $turnHint · " + String.format("%.0f", routeDistanceM) + " м",
                color = Color(0xFF7EE787), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
            )
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
                Text(
                    "› ${cmd.text}",
                    color = color,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
    }
}
