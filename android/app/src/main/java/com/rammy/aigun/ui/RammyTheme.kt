package com.rammy.aigun.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ElectricCyan = Color(0xFF5DE2FF)
val MonitorBlack = Color(0xFF050708)
val GlassBlack = Color(0xB312171A)

private val RammyColors = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF002028),
    background = MonitorBlack,
    onBackground = Color.White,
    surface = Color(0xFF101416),
    onSurface = Color.White,
    error = Color(0xFFFF4D57),
)

@Composable
fun RammyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RammyColors, content = content)
}

