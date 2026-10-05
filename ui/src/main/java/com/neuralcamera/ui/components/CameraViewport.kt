package com.neuralcamera.ui.components

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.neuralcamera.ui.theme.CameraDesignSystem

/**
 * Camera Viewport container adhering to Section 10 & Section 11 of Phase 1.
 * Integrates direct low-latency hardware SurfaceView preview while maintaining
 * the Leica/Zeiss minimalist aesthetic framing grid.
 */
@Composable
fun CameraViewport(
    modifier: Modifier = Modifier,
    onSurfaceAvailable: (Surface) -> Unit = {},
    onSurfaceDestroyed: () -> Unit = {},
    content: @Composable () -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CameraDesignSystem.Colors.AbsoluteBlack)
    ) {
        // Direct Hardware SurfaceView Preview Pipeline (Section 11)
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            onSurfaceAvailable(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int
                        ) {
                            // Surface dimension update
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            onSurfaceDestroyed()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Photographic framing guide overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = CameraDesignSystem.Spacing.controlMargin)
                .border(0.5.dp, CameraDesignSystem.Colors.FocusGridLine)
        ) {
            content()
        }
    }
}
