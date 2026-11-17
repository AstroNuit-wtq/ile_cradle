package com.astronuit.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.astronuit.app.data.BrightStarCatalog
import com.astronuit.app.data.NebulaCatalog
import com.astronuit.app.data.PlanetCatalog
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AstroNuitApp() {
    var showLaunch by remember { mutableStateOf(true) }
    var showNebulae by remember { mutableStateOf(false) }
    var isNightMode by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(Modifier = Modifier.fillMaxSize().background(Color.Black)) {
        SkyCanvas(
            scale = scale,
            offset = offset,
            onTransform = { z, pan ->
                scale = (scale * z).coerceIn(0.5f, 5f)
                offset += pan
            }
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            TopBar()
            Spacer(Modifier.weight(1f))
            BottomBar(
                isNightMode = isNightMode,
                onNightToggle = { isNightMode = !isNightMode },
                onNebulae = { showNebulae = true }
            )
        }

        if (showLaunch) {
            LaunchScreen { showLaunch = false }
        }

        if (showNebulae) {
            NebulaeScreen(onClose = { showNebulae = false })
        }
    }
}

@Composable
fun LaunchScreen(onFinish: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(1200))
        delay(800)
        onFinish()
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Astro_Nuit",
                color = Color.White.copy(alpha = alpha.value),
                fontSize = 40.sp,
                fontWeight = FontWeight.Light
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Исследуй ночное небо",
                color = Color.White.copy(alpha = 0.6f * alpha.value),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun TopBar() {
    val time = SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date())
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.LocationOn, null, tint = Color.White.copy(0.9f), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Текущее место", color = Color.White.copy(0.9f), fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(time, color = Color.White.copy(0.9f), fontSize = 13.sp)
    }
}

@Composable
fun BottomBar(isNightMode: Boolean, onNightToggle: () -> Unit, onNebulae: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = Color.White, modifier = Modifier.size(24.dp))
        IconButton(onClick = onNebulae) {
            Icon(Icons.Default.AutoAwesome, null, tint = Color.White)
        }
        Icon(Icons.Default.Tune, null, tint = Color.White, modifier = Modifier.size(24.dp))
        IconButton(onClick = onNightToggle) {
            Icon(
                if (isNightMode) Icons.Default.DarkMode else Icons.Default.Nightlight,
                null,
                tint = if (isNightMode) Color(0xFFC02015) else Color.White
            )
        }
        Icon(Icons.Default.Explore, null, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun SkyCanvas(scale: Float, offset: Offset, onTransform: (zoom: Float, pan: Offset) -> Unit) {
    val stars = remember { BrightStarCatalog.stars }
    val planets = remember { PlanetCatalog.all }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransform(zoom, pan)
                }
            }
    ) {
        val cx = size.width / 2 + offset.x
        val cy = size.height / 2 + offset.y

        for (star in stars) {
            val x = cx + star.x * scale * size.minDimension * 0.4f
            val y = cy + star.y * scale * size.minDimension * 0.4f
            val r = (3.2f - star.mag * 0.35f).coerceAtLeast(0.8f) * scale.coerceAtMost(2f)
            drawCircle(color = star.color, radius = r, center = Offset(x, y))
        }

        for (p in planets) {
            val x = cx + p.x * scale * size.minDimension * 0.4f
            val y = cy + p.y * scale * size.minDimension * 0.4f
            drawCircle(color = p.color, radius = p.size * scale, center = Offset(x, y))
        }
    }
}

@Composable
fun NebulaeScreen(onClose: () -> Unit) {
    Surface(Modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Туманности", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(NebulaCatalog.all) { nebula ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(0.1f))
                            .padding(12.dp)
                    ) {
                        AsyncImage(
                            model = nebula.imageUrl,
                            contentDescription = nebula.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(nebula.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(nebula.type, color = Color.White.copy(0.6f), fontSize = 13.sp)
                        Text(nebula.description, color = Color.White.copy(0.85f), fontSize = 14.sp)
                        Text(nebula.credit, color = Color.White.copy(0.4f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
