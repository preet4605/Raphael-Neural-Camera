package com.neuralcamera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val MatteBlack = Color(0xFF0C0D0E)
val ViewfinderBlack = Color(0xFF000000)
val DarkGunmetal = Color(0xFF14171A)
val SurfaceOnyx = Color(0xFF181B1E)
val LeicaRed = Color(0xFFE2001A)
val ZeissBlue = Color(0xFF00539B)
val PrecisionAmber = Color(0xFFFFB300)
val StudioWhite = Color(0xFFF5F6F8)
val MutedSlate = Color(0xFF8E95A0)
val NeuralActiveGreen = Color(0xFF00E676)

private val CameraColorScheme = darkColorScheme(
    primary = StudioWhite,
    onPrimary = MatteBlack,
    surface = SurfaceOnyx,
    onSurface = StudioWhite,
    background = ViewfinderBlack,
    onBackground = StudioWhite,
    secondary = PrecisionAmber,
    onSecondary = MatteBlack
)

@Composable
fun NeuralCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CameraColorScheme,
        content = content
    )
}
