package com.diplomka.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF58A6FF),
    secondary = Color(0xFF238636),
    tertiary = Color(0xFFD29922),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    error = Color(0xFFDA3633),
    onPrimary = Color.White,
    onBackground = Color(0xFFE6EDF3),
    onSurface = Color(0xFFE6EDF3)
)

@Composable
fun DiplomkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
