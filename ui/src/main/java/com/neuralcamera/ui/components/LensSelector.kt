package com.neuralcamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.neuralcamera.ui.theme.CameraDesignSystem

/**
 * LensSelector tactile optical zoom switch component (Section 17).
 */
@Composable
fun LensSelector(
    modifier: Modifier = Modifier,
    activeZoom: Float = 1.0f,
    availableZooms: List<Float> = listOf(0.6f, 1.0f, 3.0f),
    onZoomSelected: (Float) -> Unit = {}
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CameraDesignSystem.Spacing.compact),
        verticalAlignment = Alignment.CenterVertically
    ) {
        availableZooms.forEach { zoom ->
            val isSelected = (zoom == activeZoom)
            Box(
                modifier = Modifier
                    .size(CameraDesignSystem.Controls.lensButtonDiameter)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) CameraDesignSystem.Colors.MechanicalBezel
                        else CameraDesignSystem.Colors.MatteSurface.copy(alpha = 0.5f)
                    )
                    .clickable { onZoomSelected(zoom) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${zoom}x",
                    style = if (isSelected) CameraDesignSystem.Typography.shutterDisplay
                    else CameraDesignSystem.Typography.telemetry
                )
            }
        }
    }
}
