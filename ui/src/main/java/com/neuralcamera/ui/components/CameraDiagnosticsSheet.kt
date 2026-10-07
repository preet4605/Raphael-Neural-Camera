package com.neuralcamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuralcamera.ui.theme.DarkGunmetal
import com.neuralcamera.ui.theme.MatteBlack
import com.neuralcamera.ui.theme.MutedSlate
import com.neuralcamera.ui.theme.NeuralActiveGreen
import com.neuralcamera.ui.theme.PrecisionAmber
import com.neuralcamera.ui.theme.StudioWhite

private const val NOT_AVAILABLE = "N/A"

/**
 * Diagnostic telemetry data model encompassing all 20 fields mandated by Section 34.
 * Every field reads N/A until a real measurement populates it.
 */
data class CameraDiagnosticsData(
    val cameraId: String = NOT_AVAILABLE,
    val lensFacing: String = NOT_AVAILABLE,
    val physicalCameraId: String = NOT_AVAILABLE,
    val resolution: String = NOT_AVAILABLE,
    val format: String = NOT_AVAILABLE,
    val fps: Float? = null,
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusState: String = NOT_AVAILABLE,
    val aeState: String = NOT_AVAILABLE,
    val awbState: String = NOT_AVAILABLE,
    val frameNumber: Long? = null,
    val timestampNs: Long? = null,
    val queueDepth: Int? = null,
    val droppedFrames: Long? = null,
    val memoryUsageMb: Long? = null,
    val sessionConfiguration: String = NOT_AVAILABLE,
    val dynamicRange: String = NOT_AVAILABLE,
    val zslState: String = NOT_AVAILABLE,
    val sensorSync: String = NOT_AVAILABLE
)

/**
 * Debug-only diagnostic overlay sheet adhering to Section 34.
 * Completely decoupled from the consumer camera interface.
 */
@Composable
fun CameraDiagnosticsSheet(
    data: CameraDiagnosticsData,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MatteBlack.copy(alpha = 0.94f))
            .border(1.dp, PrecisionAmber.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HARDWARE CAMERA DIAGNOSTICS",
                    color = PrecisionAmber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = data.fps?.let { "$it FPS" } ?: "FPS $NOT_AVAILABLE",
                    color = NeuralActiveGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            DiagnosticRow("Camera ID", data.cameraId)
            DiagnosticRow("Lens Facing", data.lensFacing)
            DiagnosticRow("Physical Camera ID", data.physicalCameraId)
            DiagnosticRow("Active Resolution", data.resolution)
            DiagnosticRow("Hardware Format", data.format)
            DiagnosticRow("ISO Sensitivity", data.iso?.toString() ?: NOT_AVAILABLE)
            DiagnosticRow(
                "Exposure Time",
                data.exposureTimeNs?.let { "${it / 1_000_000} ms (1/${1_000_000_000L / it.coerceAtLeast(1)}s)" } ?: NOT_AVAILABLE
            )
            DiagnosticRow("Focus Distance", data.focusState)
            DiagnosticRow("Auto Exposure (AE)", data.aeState)
            DiagnosticRow("Auto White Balance", data.awbState)
            DiagnosticRow("Frame Sequence #", data.frameNumber?.toString() ?: NOT_AVAILABLE)
            DiagnosticRow("Sensor Timestamp", data.timestampNs?.let { "$it ns" } ?: NOT_AVAILABLE)
            DiagnosticRow("Buffer Queue Depth", data.queueDepth?.let { "$it frames" } ?: NOT_AVAILABLE)
            DiagnosticRow("Dropped Frames", data.droppedFrames?.toString() ?: NOT_AVAILABLE)
            DiagnosticRow("Process Memory (PSS)", data.memoryUsageMb?.let { "$it MB" } ?: NOT_AVAILABLE)
            DiagnosticRow("Session Configuration", data.sessionConfiguration)
            DiagnosticRow("Dynamic Range Profile", data.dynamicRange)
            DiagnosticRow("Zero Shutter Lag (ZSL)", data.zslState)
            DiagnosticRow("IMU Sensor Sync", data.sensorSync)
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label.uppercase(),
            color = MutedSlate,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = value,
            color = StudioWhite,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}
