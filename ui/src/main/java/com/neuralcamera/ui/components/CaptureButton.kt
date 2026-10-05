package com.neuralcamera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.neuralcamera.ui.theme.CameraDesignSystem

/**
 * CaptureButton mechanical shutter release component (Section 17).
 */
@Composable
fun CaptureButton(
    modifier: Modifier = Modifier,
    isCapturing: Boolean = false,
    onShutterPressed: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .size(CameraDesignSystem.Controls.shutterOuterDiameter)
            .border(3.dp, CameraDesignSystem.Colors.PureWhite, CircleShape)
            .clickable { onShutterPressed() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(CameraDesignSystem.Controls.shutterInnerDiameter)
                .clip(CircleShape)
                .background(
                    if (isCapturing) CameraDesignSystem.Colors.AuthenticRed
                    else CameraDesignSystem.Colors.PureWhite
                )
        )
    }
}
