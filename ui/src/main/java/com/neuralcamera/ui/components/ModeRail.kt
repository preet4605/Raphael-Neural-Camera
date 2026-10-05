package com.neuralcamera.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.neuralcamera.capture.CameraShootingMode
import com.neuralcamera.ui.theme.CameraDesignSystem

/**
 * ModeRail shooting mode carousel component (Section 17).
 */
@Composable
fun ModeRail(
    modifier: Modifier = Modifier,
    activeMode: CameraShootingMode = CameraShootingMode.AUTO,
    onModeSelected: (CameraShootingMode) -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = CameraDesignSystem.Spacing.compact),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CameraShootingMode.values().forEach { mode ->
            val isSelected = (mode == activeMode)
            Text(
                text = mode.name,
                style = if (isSelected) {
                    CameraDesignSystem.Typography.modeLabel.copy(
                        color = CameraDesignSystem.Colors.PrecisionAmber
                    )
                } else {
                    CameraDesignSystem.Typography.modeLabel.copy(
                        color = CameraDesignSystem.Colors.SubtleMuted
                    )
                },
                modifier = Modifier.clickable { onModeSelected(mode) }
            )
        }
    }
}
