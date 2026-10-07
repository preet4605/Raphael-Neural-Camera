package com.neuralcamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.neuralcamera.ui.theme.CameraDesignSystem

/**
 * TopBar component placeholder (Section 17).
 */
@Composable
fun TopBar(
    modifier: Modifier = Modifier,
    flashState: String = "AUTO",
    aspectRatio: String = "4:3",
    rawEnabled: Boolean = false, // the camera UI captures YUV; RAW capture exists only in the Gate 1 probe
    onSettingsClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CameraDesignSystem.Spacing.topBarHeight)
            .background(CameraDesignSystem.Colors.ViewfinderObsidian.copy(alpha = 0.85f))
            .padding(horizontal = CameraDesignSystem.Spacing.standard),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "FLASH $flashState", style = CameraDesignSystem.Typography.telemetry)
        Text(text = aspectRatio, style = CameraDesignSystem.Typography.shutterDisplay)
        Text(
            text = if (rawEnabled) "RAW" else "JPEG",
            style = CameraDesignSystem.Typography.statusBadge
        )
    }
}
