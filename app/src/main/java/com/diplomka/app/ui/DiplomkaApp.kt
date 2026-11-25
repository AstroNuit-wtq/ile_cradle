package com.diplomka.app.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DiplomkaApp() {
    val permissions = rememberMultiplePermissionsState(
        listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    )
    LaunchedEffect(Unit) {
        if (!permissions.allPermissionsGranted) permissions.launchMultiplePermissionRequest()
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0D1117)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Diplomka", color = Color(0xFF58A6FF), fontSize = 28.sp)
            Text(
                "Полный UI — в Diplomka_sources.zip.\nЗамените этот файл и CameraHelpers.kt из архива.",
                color = Color.White,
                fontSize = 14.sp
            )
            if (permissions.allPermissionsGranted) {
                Text("Разрешения OK", color = Color(0xFF3FB950))
            } else {
                Button(onClick = { permissions.launchMultiplePermissionRequest() }) {
                    Text("Выдать разрешения")
                }
            }
        }
    }
}
