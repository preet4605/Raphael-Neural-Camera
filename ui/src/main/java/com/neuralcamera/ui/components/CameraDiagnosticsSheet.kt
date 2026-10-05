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

/**
 * Diagnostic telemetry data model encompassing all 20 fields mandated by Section 34.
 */
data class CameraDiagnosticsData(
    val cameraId: String = "0",
    val lensFacing: String = "BACK_WIDE",
    val physicalCameraId: String = "0",
    val resolution: String = "4000x3000 (12 MP)",
    val format: String = "RAW_SENSOR / YUV_420_888",
    val fps: Float = 59.8f,
    val iso: Int = 100,
    val exposureTimeNs: Long = 10_000_000L, // 1/100s
    val focusState: String = "LOCKED_FOCUSED (1.2m)",
    val aeState: String = "CONVERGED",
    val awbState: String = "CONVERGED",
    val frameNumber: Long = 1420L,
    val timestampNs: Long = 284920491823L,
    val queueDepth: Int = 2,
    val droppedFrames: Long = 0L,
    val memoryUsageMb: Long = 184L,
    val sessionConfiguration: String = "PREVIEW (1080p) + STILL (RAW10)",
    val dynamicRange: String = "HLG10 / SDR",
    val zslState: String = "STANDBY (Ring Bounded)",
    val sensorSync: String = "SYNCED (avg offset 1.2ms, jitter 0.4ms)"
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
                    text = "${data.fps} FPS",
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
            DiagnosticRow("ISO Sensitivity", "${data.iso}")
            DiagnosticRow("Exposure Time", "${data.exposureTimeNs / 1_000_000} ms (1/${(1_000_000_000L / data.exposureTimeNs.coerceAtLeast(1))}s)")
            DiagnosticRow("Focus Distance", data.focusState)
            DiagnosticRow("Auto Exposure (AE)", data.aeState)
            DiagnosticRow("Auto White Balance", data.awbState)
            DiagnosticRow("Frame Sequence #", "${data.frameNumber}")
            DiagnosticRow("Sensor Timestamp", "${data.timestampNs} ns")
            DiagnosticRow("Buffer Queue Depth", "${data.queueDepth} frames")
            DiagnosticRow("Dropped Frames", "${data.droppedFrames}")
            DiagnosticRow("Process Memory (PSS)", "${data.memoryUsageMb} MB / 512 MB ceiling")
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
