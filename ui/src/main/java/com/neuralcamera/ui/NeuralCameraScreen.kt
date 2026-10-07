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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

/**
 * PRO manual exposure controls. Present only when the camera reported manual-sensor ranges; the option lists are the
 * standard stops inside those ranges.
 */
data class ProExposureControls(
    val isoLabels: List<String>,
    val isoIndex: Int,
    val shutterLabels: List<String>,
    val shutterIndex: Int,
    /** False: camera auto exposure (the values shown are not applied). */
    val manual: Boolean
)

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
    /** Neutral feedback (capture saved, mode/zoom changed); shown in white, errors in red. */
    val infoMessage: String? = null,
    /** Zoom ratios the active camera accepts; buttons outside it are dimmed. Null = not known yet. */
    val supportedZoom: ClosedFloatingPointRange<Float>? = null,
    /** Manual exposure controls for PRO; null when the camera has not reported manual-sensor support. */
    val proExposure: ProExposureControls? = null,
    val diagnosticsData: CameraDiagnosticsData = CameraDiagnosticsData()
)

@Composable
fun NeuralCameraScreen(
    state: CameraUIState = CameraUIState(),
    onModeSelected: (CameraShootingMode) -> Unit = {},
    onZoomSelected: (Float) -> Unit = {},
    onShutterPressed: () -> Unit = {},
    onToggleDiagnostics: () -> Unit = {},
    onManualExposureToggled: () -> Unit = {},
    onIsoStep: (Int) -> Unit = {},
    onShutterStep: (Int) -> Unit = {},
    onSurfaceAvailable: (Surface) -> Unit = {},
    onSurfaceDestroyed: () -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
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
                        text = CapabilityPresentation.processingBadge(state.isNeuralActive, state.neuralBackendName),
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
                    // Vertical padding brings the touch target to 48 dp; the bar is 64 dp tall.
                    modifier = Modifier
                        .clickable(role = Role.Button) { onToggleDiagnostics() }
                        .padding(vertical = 18.dp, horizontal = 4.dp)
                )
            }

            // Lens Selector (Tactile Optical Selector)
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 182.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(DarkGunmetal.copy(alpha = 0.75f))
                    .padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (zoom in CapabilityPresentation.zoomButtons(state.supportedZoom)) {
                    val isSelected = (state.activeZoomFactor == zoom)
                    // 48 dp touch target around a 36 dp chip.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onZoomSelected(zoom)
                            }
                            .semantics {
                                contentDescription = CapabilityPresentation.zoomDescription(zoom, isSelected)
                                selected = isSelected
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) PrecisionAmber else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = CapabilityPresentation.zoomLabel(zoom),
                                color = if (isSelected) MatteBlack else StudioWhite,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = FontFamily.Monospace
                            )
                        }
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
                        .padding(bottom = 246.dp, start = 16.dp, end = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MatteBlack.copy(alpha = 0.85f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            state.infoMessage?.takeIf { state.statusMessage == null }?.let { message ->
                Text(
                    text = message,
                    color = StudioWhite,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 246.dp, start = 16.dp, end = 16.dp)
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
                val pro = state.proExposure
                if (state.activeMode == CameraShootingMode.PRO && pro != null) {
                    ProExposureRow(pro, onManualExposureToggled, onIsoStep, onShutterStep)
                }

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
                            // 13 sp text + 2 x 16 dp padding reaches the 48 dp minimum touch height.
                            modifier = Modifier
                                .clickable(role = Role.Tab) { onModeSelected(mode) }
                                .semantics { selected = isSelected }
                                .padding(horizontal = 8.dp, vertical = 16.dp)
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
                    // No gallery viewer or camera switch exists yet: keep their space, show no control for them.
                    Spacer(modifier = Modifier.size(48.dp))

                    // Tactile Shutter Button
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .border(3.5.dp, StudioWhite, CircleShape)
                            .padding(6.dp)
                            .clickable(enabled = !state.isCapturing, onClickLabel = "Take photo", role = Role.Button) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onShutterPressed()
                            }
                            .semantics { contentDescription = if (state.isCapturing) "Shutter, capturing" else "Shutter" },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(if (state.isCapturing) PrecisionAmber else if (state.activeMode == CameraShootingMode.MASTER) LeicaRed else StudioWhite)
                        )
                    }

                    Spacer(modifier = Modifier.size(48.dp))
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

/** AUTO/MANUAL toggle and ISO / shutter steppers; values are dimmed while auto exposure is in charge. */
@Composable
private fun ProExposureRow(
    pro: ProExposureControls,
    onToggle: () -> Unit,
    onIsoStep: (Int) -> Unit,
    onShutterStep: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (pro.manual) "MANUAL" else "AUTO EXP",
            color = if (pro.manual) PrecisionAmber else MutedSlate,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clickable(role = Role.Switch) { onToggle() }
                .semantics { contentDescription = if (pro.manual) "Manual exposure on" else "Manual exposure off" }
                .padding(horizontal = 8.dp, vertical = 16.dp)
        )
        Stepper("ISO", pro.isoLabels.getOrNull(pro.isoIndex) ?: "-", pro.manual, onIsoStep)
        Stepper("Shutter", pro.shutterLabels.getOrNull(pro.shutterIndex) ?: "-", pro.manual, onShutterStep)
    }
}

@Composable
private fun Stepper(name: String, value: String, enabled: Boolean, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for ((delta, glyph) in listOf(-1 to "‹", 1 to "›")) {
            if (delta == 1) {
                Text(
                    text = if (name == "ISO") "ISO $value" else value,
                    color = if (enabled) StudioWhite else MutedSlate,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(enabled = enabled, role = Role.Button) { onStep(delta) }
                    .semantics { contentDescription = "$name ${if (delta < 0) "down" else "up"}, now $value" },
                contentAlignment = Alignment.Center
            ) {
                Text(text = glyph, color = if (enabled) PrecisionAmber else MutedSlate, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
