package com.neuralcamera.capture.scene

import com.neuralcamera.capture.motion.MotionClass
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow

/*
 * Scene understanding from measurable signals only: the luma histogram of a preview/analysis frame, the capture result's
 * exposure, and the gyro motion class. Illumination is estimated from exposure (EV100), not from a light sensor.
 * Semantic signals (subject, sky, faces) need a detector that does not exist yet; they are reported as NOT_AVAILABLE,
 * never guessed. Thresholds are design defaults, NOT_TESTED against real OnePlus 15 captures.
 */

/** Luma statistics of one 8-bit frame. */
data class LumaStats(
    val mean: Double,
    /** Fraction of pixels >= 250. */
    val highlightClipFraction: Double,
    /** Fraction of pixels <= 5. */
    val shadowCrushFraction: Double,
    /** Mean absolute 4-neighbour Laplacian (texture energy) on 8-bit values. */
    val textureEnergy: Double,
    val p02: Int,
    val p98: Int
) {
    companion object {
        fun of(luma: ByteArray, width: Int, height: Int): LumaStats {
            require(width * height == luma.size && width >= 3 && height >= 3) { "size mismatch" }
            val hist = IntArray(256)
            var sum = 0L
            for (b in luma) { val v = b.toInt() and 0xFF; hist[v]++; sum += v }
            val n = luma.size.toDouble()
            var tex = 0.0; var tn = 0
            for (y in 1 until height - 1) for (x in 1 until width - 1) {
                val i = y * width + x
                val c = luma[i].toInt() and 0xFF
                val l = 4 * c - (luma[i - 1].toInt() and 0xFF) - (luma[i + 1].toInt() and 0xFF) -
                    (luma[i - width].toInt() and 0xFF) - (luma[i + width].toInt() and 0xFF)
                tex += kotlin.math.abs(l); tn++
            }
            return LumaStats(
                mean = sum / n,
                highlightClipFraction = (250..255).sumOf { hist[it] } / n,
                shadowCrushFraction = (0..5).sumOf { hist[it] } / n,
                textureEnergy = tex / tn,
                p02 = percentile(hist, luma.size, 0.02),
                p98 = percentile(hist, luma.size, 0.98)
            )
        }

        private fun percentile(hist: IntArray, n: Int, q: Double): Int {
            val target = q * n
            var acc = 0
            for (v in 0..255) { acc += hist[v]; if (acc >= target) return v }
            return 255
        }
    }
}

enum class IlluminationClass { DAYLIGHT, BRIGHT_INDOOR, DIM_INDOOR, LOW_LIGHT, NIGHT, UNKNOWN }

enum class SignalAvailability { MEASURED, NOT_AVAILABLE }

data class SemanticSignal(val name: String, val availability: SignalAvailability, val value: Double? = null)

data class SceneAssessment(
    /** Scene EV at ISO 100, from exposure and mean luma; null when exposure is unknown. */
    val ev100: Double?,
    /** Approximate scene illuminance (lux) derived from [ev100]; an estimate, not a light-meter reading. */
    val estimatedLux: Double?,
    val illumination: IlluminationClass,
    val hdrScene: Boolean,
    val nightScene: Boolean,
    val motion: MotionClass,
    val highlightRisk: Double,
    val textureLevel: Double,
    /** 0..1: how much processing the scene is likely to need (more frames, HDR merge, longer budget). */
    val complexity: Double,
    val semanticSignals: List<SemanticSignal>,
    val reasons: List<String>
)

object SceneAnalyzer {
    /** Mean luma of an 18% grey target after a typical display tone curve, in 8-bit. */
    const val MID_GREY_8BIT = 118.0

    fun assess(
        stats: LumaStats,
        exposureTimeNs: Long?,
        iso: Int?,
        aperture: Float?,
        motion: MotionClass
    ): SceneAssessment {
        val reasons = ArrayList<String>()
        val ev100 = if (exposureTimeNs != null && exposureTimeNs > 0 && iso != null && iso > 0 && aperture != null && aperture > 0) {
            val n = aperture.toDouble()
            val t = exposureTimeNs / 1e9
            // EV100 of the exposure, corrected by how far the frame's mean sits from mid-grey (approximate; the
            // frame is tone-mapped, so the correction is only a first-order estimate).
            log2(n * n / t) - log2(iso / 100.0) + log2(stats.mean.coerceAtLeast(1.0) / MID_GREY_8BIT)
        } else {
            reasons.add("exposure time, ISO or aperture missing: illumination unknown"); null
        }
        val lux = ev100?.let { 2.5 * 2.0.pow(it) }
        val illumination = when {
            ev100 == null -> IlluminationClass.UNKNOWN
            ev100 >= 12 -> IlluminationClass.DAYLIGHT
            ev100 >= 8 -> IlluminationClass.BRIGHT_INDOOR
            ev100 >= 5 -> IlluminationClass.DIM_INDOOR
            ev100 >= 2 -> IlluminationClass.LOW_LIGHT
            else -> IlluminationClass.NIGHT
        }
        val hdr = stats.highlightClipFraction > 0.01 && stats.shadowCrushFraction > 0.05 ||
            stats.highlightClipFraction > 0.03 && stats.p02 < 15
        if (hdr) reasons.add("clipped highlights with crushed shadows")
        val night = illumination == IlluminationClass.NIGHT || illumination == IlluminationClass.LOW_LIGHT
        val texture = (stats.textureEnergy / 40.0).coerceIn(0.0, 1.0)
        val motionTerm = when (motion) { MotionClass.STILL -> 0.0; MotionClass.HANDHELD -> 0.3; MotionClass.MOVING -> 0.7; MotionClass.UNKNOWN -> 0.5 }
        val lightTerm = when (illumination) {
            IlluminationClass.DAYLIGHT -> 0.0; IlluminationClass.BRIGHT_INDOOR -> 0.2; IlluminationClass.DIM_INDOOR -> 0.4
            IlluminationClass.LOW_LIGHT -> 0.7; IlluminationClass.NIGHT -> 1.0; IlluminationClass.UNKNOWN -> 0.5
        }
        val complexity = (0.4 * lightTerm + 0.25 * (if (hdr) 1.0 else 0.0) + 0.2 * motionTerm + 0.15 * texture).coerceIn(0.0, 1.0)
        return SceneAssessment(
            ev100, lux, illumination, hdr, night, motion,
            highlightRisk = (stats.highlightClipFraction / 0.05).coerceIn(0.0, 1.0),
            textureLevel = texture,
            complexity = complexity,
            semanticSignals = listOf("subject", "sky", "face", "text").map { SemanticSignal(it, SignalAvailability.NOT_AVAILABLE) },
            reasons = reasons
        )
    }

    /** Dynamic range of the frame in stops between the 2nd and 98th percentile (display-referred, approximate). */
    fun displayRangeStops(stats: LumaStats): Double = ln((stats.p98 + 1.0) / (stats.p02 + 1.0)) / ln(2.0)
}
