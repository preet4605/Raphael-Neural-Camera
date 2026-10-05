package com.neuralcamera.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CameraDesignSystem establishes the foundational visual and tactile language
 * for Neural Camera, adhering to Section 16 of the Constitution.
 * Inspired by the mechanical precision and restraint of Leica, Zeiss, and Hasselblad.
 */
object CameraDesignSystem {

    object Colors {
        val AbsoluteBlack = Color(0xFF000000)
        val ViewfinderObsidian = Color(0xFF0A0B0D)
        val MatteSurface = Color(0xFF14171A)
        val MechanicalBezel = Color(0xFF1E2227)
        val OpticalChrome = Color(0xFFE8ECEF)
        val PureWhite = Color(0xFFFFFFFF)
        val SubtleMuted = Color(0xFF7E8691)
        val FocusGridLine = Color(0x1AFFFFFF)

        // Precision Accents
        val PrecisionAmber = Color(0xFFFFB300)
        val AuthenticRed = Color(0xFFE2001A)
        val NeuralActiveGreen = Color(0xFF00E676)
        val CalibratedCyan = Color(0xFF00D2FF)
    }

    object Spacing {
        val micro: Dp = 2.dp
        val tight: Dp = 4.dp
        val compact: Dp = 8.dp
        val standard: Dp = 16.dp
        val comfortable: Dp = 24.dp
        val generous: Dp = 32.dp
        val controlMargin: Dp = 48.dp
        val shutterBarHeight: Dp = 120.dp
        val topBarHeight: Dp = 56.dp
    }

    object Typography {
        val shutterDisplay = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
            color = Colors.OpticalChrome
        )
        val modeLabel = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 1.2.sp,
            color = Colors.OpticalChrome
        )
        val telemetry = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
            color = Colors.SubtleMuted
        )
        val statusBadge = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            color = Colors.NeuralActiveGreen
        )
    }

    object Controls {
        val shutterOuterDiameter: Dp = 72.dp
        val shutterInnerDiameter: Dp = 58.dp
        val lensButtonDiameter: Dp = 38.dp
        val dialIndicatorStroke: Dp = 2.dp
    }

    object Animation {
        const val shutterPressDurationMs: Int = 90
        const val modeSwitchDurationMs: Int = 180
        const val focusLockDurationMs: Int = 120
    }

    object Haptics {
        const val shutterPressHapticType: String = "HEAVY_CLICK"
        const val dialStepHapticType: String = "TICK"
        const val focusLockHapticType: String = "DOUBLE_CLICK"
    }
}
