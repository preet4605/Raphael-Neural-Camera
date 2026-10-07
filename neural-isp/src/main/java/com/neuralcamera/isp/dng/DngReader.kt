package com.neuralcamera.isp.dng

import com.neuralcamera.isp.temporal.BayerFrame
import com.neuralcamera.isp.temporal.BayerRadiometry
import com.neuralcamera.isp.temporal.FloatPlane
import com.neuralcamera.isp.temporal.NoiseModel
import com.neuralcamera.isp.temporal.U16Plane
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Colour of each CFA site in raster order of the 2x2 cell (0 = red, 1 = green, 2 = blue). */
enum class CfaPattern(val colors: IntArray) {
    RGGB(intArrayOf(0, 1, 1, 2)),
    GRBG(intArrayOf(1, 0, 2, 1)),
    GBRG(intArrayOf(1, 2, 0, 1)),
    BGGR(intArrayOf(2, 1, 1, 0));

    companion object {
        fun fromColors(colors: IntArray): CfaPattern? = entries.firstOrNull { it.colors.contentEquals(colors) }
    }
}

/**
 * A raw Bayer image read from a DNG. The mosaic is cropped to the DNG's ActiveArea when one is present, and every
 * per-CFA-position array (black levels, noise) is expressed in the cropped mosaic's own 2x2 raster order.
 */
class DngRawImage(
    val width: Int,
    val height: Int,
    val cfa: CfaPattern,
    /** Black level per CFA raster position. */
    val blackLevels: DoubleArray,
    val whiteLevel: Double,
    /** Noise model per CFA raster position, in normalized units, if the DNG carries a NoiseProfile. */
    val noise: List<NoiseModel>?,
    val asShotNeutral: DoubleArray?,
    val exposureTimeSeconds: Double?,
    val iso: Int?,
    val mosaic: U16Plane,
    /** Human-readable notes on anything assumed or ignored while reading. */
    val notes: List<String>,
    /** DNG ColorMatrix1 (XYZ to camera, row-major) and ForwardMatrix1 (white-balanced camera to XYZ D50), if present. */
    val colorMatrix1: DoubleArray? = null,
    val forwardMatrix1: DoubleArray? = null,
    /** DNG CalibrationIlluminant1 (EXIF LightSource code), if present. */
    val calibrationIlluminant1: Int? = null,
    val uniqueCameraModel: String? = null,
    /** Lens shading gains from the OpcodeList2 GainMaps, over this (cropped) mosaic; null when absent or unsupported. */
    val lensShading: com.neuralcamera.isp.calibration.LensShadingMap? = null,
    /** Second calibration set (DNG ColorMatrix2, ForwardMatrix2, CalibrationIlluminant2), if present. */
    val colorMatrix2: DoubleArray? = null,
    val forwardMatrix2: DoubleArray? = null,
    val calibrationIlluminant2: Int? = null
) {
    fun toColorTransform() = com.neuralcamera.isp.color.ColorTransform.fromCalibration(
        asShotNeutral, colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2, calibrationIlluminant1, calibrationIlluminant2
    )

    /** The same image with a cropped mosaic. The shading map is dropped: it spans the uncropped area. */
    fun withMosaic(newMosaic: U16Plane) = DngRawImage(
        newMosaic.width, newMosaic.height, cfa, blackLevels, whiteLevel, noise, asShotNeutral, exposureTimeSeconds, iso, newMosaic,
        notes, colorMatrix1, forwardMatrix1, calibrationIlluminant1, uniqueCameraModel, null, colorMatrix2, forwardMatrix2, calibrationIlluminant2
    )

    fun toBayerFrame(gain: Double = 1.0) = BayerFrame(mosaic, BayerRadiometry(blackLevels, whiteLevel, gain))
}

object DngReader {
    private const val TAG_CFA_REPEAT_DIM = 33421
    private const val TAG_CFA_PATTERN = 33422
    private const val TAG_BLACK_LEVEL_REPEAT_DIM = 50713
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717
    private const val TAG_AS_SHOT_NEUTRAL = 50728
    private const val TAG_ACTIVE_AREA = 50829
    private const val TAG_COLOR_MATRIX_1 = 50721
    private const val TAG_FORWARD_MATRIX_1 = 50964
    private const val TAG_COLOR_MATRIX_2 = 50722
    private const val TAG_FORWARD_MATRIX_2 = 50965
    private const val TAG_CALIBRATION_ILLUMINANT_2 = 50779
    private const val TAG_CALIBRATION_ILLUMINANT_1 = 50778
    private const val TAG_UNIQUE_CAMERA_MODEL = 50708
    private const val TAG_NOISE_PROFILE = 51041
    private const val TAG_EXPOSURE_TIME = 33434
    private const val TAG_ISO = 34855
    private const val TAG_OPCODE_LIST_2 = 51009
    private const val PHOTOMETRIC_CFA = 32803L

    @Throws(TiffFormatException::class)
    fun read(file: File): DngRawImage = TiffFile.open(file).use { tiff ->
        val notes = mutableListOf<String>()
        val rawIfd = tiff.findIfd { it.longs(TiffFile.TAG_PHOTOMETRIC)?.firstOrNull() == PHOTOMETRIC_CFA }
            ?: throw TiffFormatException("${file.name}: no CFA (Photometric 32803) image IFD found")
        fun lookup(tag: Int) = rawIfd.entry(tag) ?: tiff.ifds.firstNotNullOfOrNull { it.entry(tag) }

        val width = rawIfd.longs(TiffFile.TAG_IMAGE_WIDTH)?.firstOrNull()?.toInt() ?: throw TiffFormatException("missing ImageWidth")
        val height = rawIfd.longs(TiffFile.TAG_IMAGE_LENGTH)?.firstOrNull()?.toInt() ?: throw TiffFormatException("missing ImageLength")
        val bits = rawIfd.longs(TiffFile.TAG_BITS_PER_SAMPLE)?.firstOrNull()?.toInt() ?: 16
        if (bits != 16) throw TiffFormatException("${file.name}: only 16-bit raw data is supported, found $bits")
        if ((rawIfd.longs(TiffFile.TAG_COMPRESSION)?.firstOrNull() ?: 1L) != 1L) throw TiffFormatException("${file.name}: compressed raw data is not supported")
        if ((rawIfd.longs(TiffFile.TAG_SAMPLES_PER_PIXEL)?.firstOrNull() ?: 1L) != 1L) throw TiffFormatException("${file.name}: expected one sample per pixel")

        val repeat = lookup(TAG_CFA_REPEAT_DIM)?.longs()
        if (repeat != null && !(repeat.size == 2 && repeat[0] == 2L && repeat[1] == 2L)) throw TiffFormatException("${file.name}: only 2x2 CFA patterns are supported")
        val cfaColors = lookup(TAG_CFA_PATTERN)?.longs()?.map { it.toInt() }?.toIntArray() ?: throw TiffFormatException("${file.name}: missing CFAPattern")
        var cfa = CfaPattern.fromColors(cfaColors) ?: throw TiffFormatException("${file.name}: unsupported CFA colours ${cfaColors.toList()}")

        val data = tiff.readImageData(rawIfd)
        if (data.size.toLong() != width.toLong() * height * 2) throw TiffFormatException("${file.name}: raw data is ${data.size} bytes, expected ${width.toLong() * height * 2}")
        val shorts = ShortArray(width * height)
        ByteBuffer.wrap(data).order(tiff.order).asShortBuffer().get(shorts)

        var blacks = expandBlackLevels(lookup(TAG_BLACK_LEVEL_REPEAT_DIM)?.longs(), lookup(TAG_BLACK_LEVEL)?.doubles(), notes)
        val white = lookup(TAG_WHITE_LEVEL)?.doubles()?.firstOrNull() ?: ((1 shl bits) - 1).toDouble().also { notes.add("WhiteLevel missing; assumed ${it.toInt()}") }
        var noise = lookup(TAG_NOISE_PROFILE)?.doubles()?.let { profileToPositions(it, cfa, notes) }
        if (noise == null) notes.add("no NoiseProfile in the DNG; callers must supply a noise model")
        val neutral = lookup(TAG_AS_SHOT_NEUTRAL)?.doubles()?.takeIf { it.size == 3 && it.all { v -> v.isFinite() && v > 0 } }

        // Crop to the active area, rotating the per-position arrays when the crop moves the CFA phase.
        var mosaic = U16Plane(width, height, shorts)
        val active = lookup(TAG_ACTIVE_AREA)?.longs()
        // GainMap coordinates span the ActiveArea before the crop below trims it to even dimensions.
        var gainMapArea = width to height
        if (active != null && active.size == 4) {
            val top = active[0].toInt()
            val left = active[1].toInt()
            val bottom = active[2].toInt()
            val right = active[3].toInt()
            if (top in 0 until bottom && left in 0 until right && bottom <= height && right <= width &&
                (top != 0 || left != 0 || bottom != height || right != width)
            ) {
                val w2 = (right - left) and 1.inv()
                val h2 = (bottom - top) and 1.inv()
                val cropped = ShortArray(w2 * h2)
                for (y in 0 until h2) System.arraycopy(shorts, (top + y) * width + left, cropped, y * w2, w2)
                mosaic = U16Plane(w2, h2, cropped)
                gainMapArea = (right - left) to (bottom - top)
                val shift = IntArray(4) { p -> ((p / 2 + top) % 2) * 2 + ((p % 2) + left) % 2 }
                cfa = CfaPattern.fromColors(IntArray(4) { cfa.colors[shift[it]] })!!
                blacks = DoubleArray(4) { blacks[shift[it]] }
                noise = noise?.let { n -> List(4) { n[shift[it]] } }
                notes.add("cropped to ActiveArea top=$top left=$left bottom=$bottom right=$right (${w2}x$h2)")
            }
        }

        val exposure = lookup(TAG_EXPOSURE_TIME)?.doubles()?.firstOrNull()?.takeIf { it.isFinite() && it > 0 }
        val iso = lookup(TAG_ISO)?.longs()?.firstOrNull()?.toInt()?.takeIf { it > 0 }
        val colorMatrix = lookup(TAG_COLOR_MATRIX_1)?.doubles()?.takeIf { it.size == 9 }
        val forwardMatrix = lookup(TAG_FORWARD_MATRIX_1)?.doubles()?.takeIf { it.size == 9 }
        if (colorMatrix == null && forwardMatrix == null) notes.add("no ColorMatrix1/ForwardMatrix1 in the DNG; colour is white-balance only")
        val shading = rawIfd.entry(TAG_OPCODE_LIST_2)?.let { DngGainMap.parseOpcodeList(it.data, notes) }
            ?.let { DngGainMap.toLensShadingMap(it, cfa, gainMapArea.first, gainMapArea.second, notes) }
        if (shading == null) notes.add("no usable lens shading GainMap in the DNG")
        DngRawImage(mosaic.width, mosaic.height, cfa, blacks, white, noise, neutral, exposure, iso, mosaic, notes, colorMatrix, forwardMatrix,
            lookup(TAG_CALIBRATION_ILLUMINANT_1)?.longs()?.firstOrNull()?.toInt(),
            lookup(TAG_UNIQUE_CAMERA_MODEL)?.longs()?.let { String(ByteArray(it.size) { i -> it[i].toByte() }, Charsets.US_ASCII).trimEnd('\u0000') },
            shading,
            lookup(TAG_COLOR_MATRIX_2)?.doubles()?.takeIf { it.size == 9 },
            lookup(TAG_FORWARD_MATRIX_2)?.doubles()?.takeIf { it.size == 9 },
            lookup(TAG_CALIBRATION_ILLUMINANT_2)?.longs()?.firstOrNull()?.toInt())
    }

    private fun expandBlackLevels(repeat: LongArray?, values: DoubleArray?, notes: MutableList<String>): DoubleArray {
        if (values == null || values.isEmpty()) {
            notes.add("BlackLevel missing; assumed 0")
            return DoubleArray(4)
        }
        val rows = repeat?.getOrNull(0)?.toInt() ?: 1
        val cols = repeat?.getOrNull(1)?.toInt() ?: 1
        if (rows * cols > values.size || rows !in 1..2 || cols !in 1..2) {
            notes.add("unsupported BlackLevel layout (repeat ${rows}x$cols, ${values.size} values); used the first value for every position")
            return DoubleArray(4) { values[0] }
        }
        // A 1x2 / 2x1 / 1x1 pattern repeats across the 2x2 cell.
        return DoubleArray(4) { values[((it / 2) % rows) * cols + (it % 2) % cols] }
    }

    /** NoiseProfile holds one (S, O) pair per colour plane (3: R, G, B) or per CFA position (4). */
    private fun profileToPositions(profile: DoubleArray, cfa: CfaPattern, notes: MutableList<String>): List<NoiseModel>? {
        if (profile.size % 2 != 0 || profile.any { !it.isFinite() || it < 0 }) {
            notes.add("NoiseProfile has an unexpected shape (${profile.size} values); ignored")
            return null
        }
        val pairs = profile.size / 2
        fun model(i: Int) = NoiseModel(profile[2 * i], profile[2 * i + 1])
        return when (pairs) {
            1 -> List(4) { model(0) }
            3 -> List(4) { model(cfa.colors[it]) }
            4 -> List(4) { model(it) }
            else -> {
                notes.add("NoiseProfile has $pairs pairs; expected 1, 3 or 4; ignored")
                null
            }
        }
    }
}

/** Quick-look rendering of a Bayer mosaic: bilinear demosaic, white balance, sRGB curve. Preview only, not an ISP. */
object DngPreview {

    /**
     * @param mosaic values already black-subtracted and scaled to [0,1] (values may exceed 1 slightly)
     * @return interleaved RGB floats in [0,1], length width * height * 3
     */
    fun render(mosaic: FloatPlane, cfa: CfaPattern, whiteBalance: DoubleArray?): FloatArray {
        val w = mosaic.width
        val h = mosaic.height
        val out = FloatArray(w * h * 3)
        val gains = whiteBalance?.let { n -> doubleArrayOf(n[1] / n[0], 1.0, n[1] / n[2]) } ?: doubleArrayOf(1.0, 1.0, 1.0)
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (c in 0 until 3) {
                    val site = cfa.colors[(y % 2) * 2 + (x % 2)]
                    val v: Float = if (site == c) {
                        mosaic.data[y * w + x]
                    } else {
                        var sum = 0f
                        var n = 0
                        for (dy in -1..1) for (dx in -1..1) {
                            val nx = min(max(x + dx, 0), w - 1)
                            val ny = min(max(y + dy, 0), h - 1)
                            if (cfa.colors[(ny % 2) * 2 + (nx % 2)] == c) {
                                sum += mosaic.data[ny * w + nx]
                                n++
                            }
                        }
                        if (n == 0) 0f else sum / n
                    }
                    out[(y * w + x) * 3 + c] = srgb((v * gains[c]).coerceIn(0.0, 1.0)).toFloat()
                }
            }
        }
        return out
    }

    private fun srgb(linear: Double): Double = if (linear <= 0.0031308) 12.92 * linear else 1.055 * linear.pow(1.0 / 2.4) - 0.055

    /** Normalizes a raw mosaic by per-position black level and the white level. */
    fun normalize(plane: U16Plane, blacks: DoubleArray, white: Double): FloatPlane {
        val w = plane.width
        val out = FloatPlane(plane.width, plane.height)
        for (i in out.data.indices) {
            val p = ((i / w) % 2) * 2 + (i % w) % 2
            out.data[i] = (((plane.data[i].toInt() and 0xFFFF) - blacks[p]) / (white - blacks[p])).toFloat()
        }
        return out
    }
}
