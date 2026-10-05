package com.neuralcamera.ui

import android.view.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuralcamera.capture.CameraShootingMode
import com.neuralcamera.ui.components.CameraDiagnosticsData
import com.neuralcamera.ui.components.CameraDiagnosticsSheet
import com.neuralcamera.ui.components.CameraViewport
import com.neuralcamera.ui.theme.DarkGunmetal
import com.neuralcamera.ui.theme.LeicaRed
import com.neuralcamera.ui.theme.MatteBlack
import com.neuralcamera.ui.theme.MutedSlate
import com.neuralcamera.ui.theme.NeuralActiveGreen
import com.neuralcamera.ui.theme.NeuralCameraTheme
import com.neuralcamera.ui.theme.PrecisionAmber
import com.neuralcamera.ui.theme.StudioWhite
import com.neuralcamera.ui.theme.ViewfinderBlack

data class CameraUIState(
    val activeMode: CameraShootingMode = CameraShootingMode.AUTO,
    val activeZoomFactor: Float = 1.0f,
    val isNeuralActive: Boolean = false,
    val neuralBackendName: String = "NONE (no backend verified)",
    val latencyMs: Long? = null,
    val memoryUsageMb: Long? = null,
    val thermalStatus: String = "UNKNOWN",
    val realityGuardState: String = "NOT EVALUATED",
    val showDiagnostics: Boolean = false,
    val isCapturing: Boolean = false,
    val statusMessage: String? = null,
    val diagnosticsData: CameraDiagnosticsData = CameraDiagnosticsData()
)

@Composable
fun NeuralCameraScreen(
    state: CameraUIState = CameraUIState(),
    onModeSelected: (CameraShootingMode) -> Unit = {},
    onZoomSelected: (Float) -> Unit = {},
    onShutterPressed: () -> Unit = {},
    onToggleDiagnostics: () -> Unit = {},
    onSurfaceAvailable: (Surface) -> Unit = {},
    onSurfaceDestroyed: () -> Unit = {}
) {
    NeuralCameraTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ViewfinderBlack)
        ) {
            // Live Hardware Camera2 Viewport (Section 10 & 11)
            CameraViewport(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp, bottom = 140.dp),
                onSurfaceAvailable = onSurfaceAvailable,
                onSurfaceDestroyed = onSurfaceDestroyed
            ) {
                // Rule of thirds subtle grid lines overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(0.5.dp, Color.White.copy(alpha = 0.08f))
                )

                // Viewfinder focus indicator (center crosshair)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center)
                        .border(1.dp, StudioWhite.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                )
            }

            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .align(Alignment.TopCenter)
                    .background(MatteBlack.copy(alpha = 0.85f))
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Mode indicator
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (state.isNeuralActive) NeuralActiveGreen else MutedSlate)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "NEURAL",
                        color = StudioWhite,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Diagnostics Button
                Text(
                    text = if (state.showDiagnostics) "HIDE DIAGNOSTICS" else "DIAGNOSTICS",
                    color = PrecisionAmber,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { onToggleDiagnostics() }
                )
            }

            // Lens Selector (Tactile Optical Selector)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 148.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkGunmetal.copy(alpha = 0.75f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val lenses = listOf(0.6f, 1.0f, 2.0f, 3.0f, 6.0f)
                for (zoom in lenses) {
                    val isSelected = (state.activeZoomFactor == zoom)
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) PrecisionAmber else Color.Transparent)
                            .clickable { onZoomSelected(zoom) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (zoom < 1.0f) ".6" else "${zoom.toInt()}x",
                            color = if (isSelected) MatteBlack else StudioWhite,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Capture / camera error banner
            state.statusMessage?.let { message ->
                Text(
                    text = message,
                    color = LeicaRed,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 200.dp, start = 16.dp, end = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MatteBlack.copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            // Mode Selector Carousel & Shutter Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(MatteBlack.copy(alpha = 0.95f))
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Mode Carousel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (mode in CameraShootingMode.values()) {
                        val isSelected = (state.activeMode == mode)
                        Text(
                            text = mode.name,
                            color = if (isSelected) PrecisionAmber else MutedSlate,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            letterSpacing = 1.2.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable { onModeSelected(mode) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Controls: Gallery - Shutter - Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Gallery Thumbnail placeholder
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(DarkGunmetal)
                            .border(1.dp, MutedSlate.copy(alpha = 0.4f), CircleShape)
                    )

                    // Tactile Shutter Button
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .border(3.5.dp, StudioWhite, CircleShape)
                            .padding(6.dp)
                            .clickable { onShutterPressed() },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(if (state.activeMode == CameraShootingMode.MASTER) LeicaRed else StudioWhite)
                        )
                    }

                    // Camera Switch placeholder
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(DarkGunmetal),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "1x",
                            color = MutedSlate,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Diagnostics Overlay Sheet adhering to Section 34
            if (state.showDiagnostics) {
                CameraDiagnosticsSheet(
                    data = state.diagnosticsData,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 70.dp, start = 16.dp, end = 16.dp)
                )
            }
        }
    }
}
