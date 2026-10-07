package com.neuralcamera.isp.raw

import com.neuralcamera.isp.calibration.LensShadingMap
import com.neuralcamera.isp.calibration.SensorCalibration
import com.neuralcamera.isp.dng.CfaPattern
import com.neuralcamera.isp.temporal.FloatPlane
import com.neuralcamera.isp.temporal.U16Plane
import com.neuralcamera.models.execution.FallbackAction
import com.neuralcamera.models.execution.StageOutcome
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * RAW pipeline order and the front-end stages that run before the temporal merge.
 *
 * Canonical order (each stage assumes the previous ones ran):
 *   UNPACK -> BLACK_LEVEL -> DEFECT_CORRECTION -> LENS_SHADING -> [TEMPORAL_MERGE] -> WHITE_BALANCE -> DEMOSAIC
 *   -> CHROMATIC_ABERRATION -> DISTORTION -> COLOR_MATRIX -> TONE_MAP -> OUTPUT_ENCODE
 * Rationale: defects are detected on black-subtracted data before shading gains amplify them; shading is a per-pixel
 * gain on linear data; noise handling (the merge) needs linear, black-subtracted, shading-corrected data; CA and
 * distortion are geometric and act on demosaiced RGB.
 *
 * Implemented here: BLACK_LEVEL, DEFECT_CORRECTION, LENS_SHADING (plus UNPACK in RawUnpack). TEMPORAL_MERGE, DEMOSAIC,
 * WHITE_BALANCE/COLOR_MATRIX and TONE_MAP exist elsewhere (temporal/, color/). CHROMATIC_ABERRATION and DISTORTION
 * are MISSING: requesting them yields a FAILED stage with SKIP_STAGE, never a silent pass-through reported as done.
 * Every stage is validated on synthetic data only; on real OnePlus 15 RAW: NOT_TESTED.
 */

enum class RawStage(val implemented: Boolean) {
    UNPACK(true), BLACK_LEVEL(true), DEFECT_CORRECTION(true), LENS_SHADING(true), TEMPORAL_MERGE(true),
    WHITE_BALANCE(true), DEMOSAIC(true), CHROMATIC_ABERRATION(false), DISTORTION(false), COLOR_MATRIX(true),
    TONE_MAP(true), OUTPUT_ENCODE(true);

    companion object {
        val MANDATORY = setOf(UNPACK, BLACK_LEVEL, DEMOSAIC, COLOR_MATRIX, TONE_MAP, OUTPUT_ENCODE)

        /** Problems with a requested stage list: out-of-order stages, duplicates and missing mandatory stages. */
        fun validateOrder(stages: List<RawStage>): List<String> = buildList {
            if (stages.toSet().size != stages.size) add("duplicate stages")
            stages.zipWithNext().forEach { (a, b) -> if (a.ordinal >= b.ordinal) add("$b must not follow $a") }
            (MANDATORY - stages.toSet()).sortedBy { it.ordinal }.forEach { add("mandatory stage $it missing") }
        }
    }
}

class FrontEndResult(
    /** Black-subtracted, defect-corrected, shading-corrected mosaic normalized to [0, 1] at the white level. */
    val mosaic: FloatPlane,
    val stages: List<StageOutcome<Unit>>,
    val correctedDefects: Int
) {
    val degraded: Boolean get() = stages.any { !it.ranAsIntended }
}

object RawFrontEnd {
    /** A pixel is a dynamic defect when it differs from the median of its 8 same-colour neighbours by this many sigma. */
    const val DEFECT_SIGMA = 8.0

    fun process(raw: U16Plane, cal: SensorCalibration, dynamicDefects: Boolean = true): FrontEndResult {
        require(raw.width % 2 == 0 && raw.height % 2 == 0) { "a Bayer mosaic needs even dimensions" }
        val stages = ArrayList<StageOutcome<Unit>>()
        val plane = blackLevel(raw, cal.blackLevels.value, cal.whiteLevel.value)
        stages += StageOutcome.Success("BLACK_LEVEL", Unit, "per-CFA ${cal.blackLevels.source}")

        var corrected = correctDefects(plane, cal.defects)
        var dynamicCount = 0
        if (dynamicDefects) {
            val noise = cal.noise?.value
            if (noise != null) {
                dynamicCount = correctDynamicDefects(plane) { pos, v -> sqrt(noise[pos].variance(v.toDouble().coerceAtLeast(0.0))) }
                stages += StageOutcome.Success("DEFECT_CORRECTION", Unit, "table(${cal.defects.size}) + dynamic(noise-profile)")
            } else {
                stages += StageOutcome.Degraded(
                    "DEFECT_CORRECTION", Unit, intended = "table + dynamic(noise-profile)", actual = "table only",
                    reason = "no noise profile, so dynamic defect detection has no threshold"
                )
            }
        } else stages += StageOutcome.Success("DEFECT_CORRECTION", Unit, "table(${cal.defects.size})")
        corrected += dynamicCount

        val shading = cal.lensShading?.value
        if (shading != null) {
            applyLensShading(plane, cal.cfa.value, shading)
            stages += StageOutcome.Success("LENS_SHADING", Unit, "gain map ${shading.columns}x${shading.rows} (${cal.lensShading.source})")
        } else {
            stages += StageOutcome.Failed("LENS_SHADING", "no lens shading map for ${cal.camera}", FallbackAction.SKIP_STAGE)
        }
        return FrontEndResult(plane, stages, corrected)
    }

    fun blackLevel(raw: U16Plane, black: DoubleArray, white: Double): FloatPlane {
        val out = FloatPlane(raw.width, raw.height)
        for (y in 0 until raw.height) for (x in 0 until raw.width) {
            val pos = (y and 1) * 2 + (x and 1)
            // Not clamped at 0: negative values carry read noise and keep averages unbiased for the merge.
            out.data[y * raw.width + x] = ((raw.at(x, y) - black[pos]) / (white - black[pos])).toFloat()
        }
        return out
    }

    /** Replaces listed defects with the median of their same-colour neighbours. Returns how many were replaced. */
    fun correctDefects(plane: FloatPlane, defects: List<com.neuralcamera.isp.calibration.DefectPixel>): Int {
        var n = 0
        for (d in defects) {
            if (d.x !in 0 until plane.width || d.y !in 0 until plane.height) continue
            plane.data[d.y * plane.width + d.x] = sameColourMedian(plane, d.x, d.y); n++
        }
        return n
    }

    /** Detects and replaces isolated outliers. [sigmaAt] gives the noise sigma for a CFA position and signal level. */
    fun correctDynamicDefects(plane: FloatPlane, sigmaAt: (Int, Float) -> Double): Int {
        val w = plane.width; val h = plane.height
        val fixes = ArrayList<Pair<Int, Float>>()
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val med = sameColourMedian(plane, x, y)
            val v = plane.data[y * w + x]
            val sigma = sigmaAt((y and 1) * 2 + (x and 1), med)
            if (abs(v - med) > DEFECT_SIGMA * sigma) fixes.add(y * w + x to med)
        }
        fixes.forEach { (i, m) -> plane.data[i] = m }
        return fixes.size
    }

    private fun sameColourMedian(plane: FloatPlane, x: Int, y: Int): Float {
        val vals = ArrayList<Float>(8)
        for (dy in -2..2 step 2) for (dx in -2..2 step 2) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until plane.width && ny in 0 until plane.height) vals.add(plane.data[ny * plane.width + nx])
        }
        vals.sort()
        return if (vals.size % 2 == 1) vals[vals.size / 2] else (vals[vals.size / 2 - 1] + vals[vals.size / 2]) / 2
    }

    fun applyLensShading(plane: FloatPlane, cfa: CfaPattern, map: LensShadingMap) {
        val w = plane.width; val h = plane.height
        for (y in 0 until h) for (x in 0 until w) {
            val pos = (y and 1) * 2 + (x and 1)
            val g = map.interpolate(LensShadingMap.channelOf(cfa, pos), x / (w - 1.0), y / (h - 1.0))
            plane.data[y * w + x] *= g
        }
    }
}
