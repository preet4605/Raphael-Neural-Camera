package com.neuralcamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.neuralcamera.ui.theme.CameraDesignSystem

/**
 * ExposureControl placeholder component (Section 17).
 */
@Composable
fun ExposureControl(
    modifier: Modifier = Modifier,
    evCompensation: Float = 0.0f
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(CameraDesignSystem.Colors.MatteSurface.copy(alpha = 0.7f))
            .padding(horizontal = CameraDesignSystem.Spacing.compact, vertical = CameraDesignSystem.Spacing.tight)
    ) {
        Text(
            text = "EV ${if (evCompensation >= 0) "+" else ""}${evCompensation}",
            style = CameraDesignSystem.Typography.telemetry
        )
    }
}

/**
 * FocusOverlay viewfinder focus indicator (Section 17).
 */
@Composable
fun FocusOverlay(
    modifier: Modifier = Modifier,
    isLocked: Boolean = false
) {
    Box(
        modifier = modifier
            .background(CameraDesignSystem.Colors.FocusGridLine)
    )
}

/**
 * ProControlSurface manual controls panel (Section 17).
 */
@Composable
fun ProControlSurface(
    modifier: Modifier = Modifier,
    iso: Int = 100,
    shutterSpeedSec: String = "1/250s",
    whiteBalance: String = "5500K"
) {
    Row(
        modifier = modifier
            .background(CameraDesignSystem.Colors.ViewfinderObsidian.copy(alpha = 0.9f))
            .padding(CameraDesignSystem.Spacing.compact),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "ISO $iso  ", style = CameraDesignSystem.Typography.shutterDisplay)
        Text(text = "SEC $shutterSpeedSec  ", style = CameraDesignSystem.Typography.shutterDisplay)
        Text(text = "WB $whiteBalance", style = CameraDesignSystem.Typography.shutterDisplay)
    }
}

/**
 * NeuralStatus accelerator & inference health indicator (Section 17).
 */
@Composable
fun NeuralStatus(
    modifier: Modifier = Modifier,
    backendName: String = "NONE (no backend verified)",
    isAccelerated: Boolean = true
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(CameraDesignSystem.Colors.MatteSurface.copy(alpha = 0.8f))
            .padding(horizontal = CameraDesignSystem.Spacing.compact, vertical = CameraDesignSystem.Spacing.tight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "NEURAL: $backendName",
            style = if (isAccelerated) CameraDesignSystem.Typography.statusBadge
            else CameraDesignSystem.Typography.statusBadge.copy(color = CameraDesignSystem.Colors.PrecisionAmber)
        )
    }
}

/**
 * MasterStatus photographic processing status badge (Section 17).
 */
@Composable
fun MasterStatus(
    modifier: Modifier = Modifier,
    statusText: String = "READY"
) {
    Text(
        modifier = modifier,
        text = statusText,
        style = CameraDesignSystem.Typography.telemetry
    )
}

/**
 * GalleryPreview thumbnail preview component (Section 17).
 */
@Composable
fun GalleryPreview(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(CameraDesignSystem.Colors.MechanicalBezel)
    )
}
