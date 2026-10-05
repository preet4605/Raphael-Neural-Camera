package com.neuralcamera.isp.temporal

/** Unsigned 16-bit sample plane (sensor DN, row-major). RAW10/12/14 and 8-bit luma all fit this container. */
class U16Plane(val width: Int, val height: Int, val data: ShortArray) {
    init {
        require(width > 0 && height > 0) { "plane must be non-empty" }
        require(data.size == width * height) { "data has ${data.size} samples for ${width}x$height" }
    }

    fun at(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFFFF

    companion object {
        fun fromInts(width: Int, height: Int, values: IntArray): U16Plane =
            U16Plane(width, height, ShortArray(values.size) { values[it].coerceIn(0, 0xFFFF).toShort() })
    }
}

/** FP32 plane (row-major). NaN marks an invalid sample where a plane is used as an alignment proxy. */
class FloatPlane(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "plane must be non-empty" }
        require(data.size == width * height) { "data has ${data.size} samples for ${width}x$height" }
    }

    fun at(x: Int, y: Int): Float = data[y * width + x]
}

/**
 * Sensor-to-common-scale mapping of one frame. Values are normalized to [0,1] by black/white level and divided by
 * [gain], the frame's radiometric gain (exposure time x sensitivity) relative to the reference frame, so frames of
 * different exposure land on the reference's linear scale (reference gain = 1).
 */
data class Radiometry(val blackLevel: Double, val whiteLevel: Double, val gain: Double = 1.0) {
    init {
        require(whiteLevel > blackLevel) { "white level must exceed black level" }
        require(gain > 0.0) { "gain must be positive" }
    }

    val range: Double get() = whiteLevel - blackLevel

    /** DN at or above which a pixel is treated as clipped and carries no information about the scene value. */
    val saturationDn: Double get() = blackLevel + SATURATION_FRACTION * range

    fun toCommon(dn: Int): Float = (((dn - blackLevel) / range) / gain).toFloat()

    companion object {
        const val SATURATION_FRACTION = 0.985
    }
}

/**
 * Poisson-Gaussian sensor noise in normalized units (the Android SENSOR_NOISE_PROFILE / DNG NoiseProfile convention):
 * variance(x) = shot * x + read, for x in [0,1].
 */
data class NoiseModel(val shot: Double, val read: Double) {
    init {
        require(shot >= 0.0 && read >= 0.0) { "noise coefficients must be non-negative" }
    }

    fun variance(normalized: Double): Double = maxOf(shot * normalized + read, MIN_VARIANCE)

    companion object {
        const val MIN_VARIANCE = 1e-10
    }
}

/** A frame together with its radiometric scale. */
class Frame(val plane: U16Plane, val radiometry: Radiometry)
