package com.neuralcamera.isp.encode

import com.neuralcamera.isp.dng.CfaPattern
import com.neuralcamera.isp.temporal.FloatPlane
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Everything a DNG needs besides the pixels. Values come from the capture; nothing here has defaults that pretend to be calibration. */
class DngMetadata(
    val cfa: CfaPattern,
    /** Black level per CFA raster position (same order as the mosaic's 2x2 cell). */
    val blackLevels: DoubleArray,
    val whiteLevel: Double,
    val asShotNeutral: DoubleArray,
    /** XYZ to camera, row-major 9 values, for [calibrationIlluminant1]. At least one of the two matrices is required. */
    val colorMatrix1: DoubleArray?,
    /** White-balanced camera to XYZ D50, row-major 9 values. */
    val forwardMatrix1: DoubleArray?,
    /** EXIF LightSource code for the matrices (21 = D65, 17 = standard light A, ...). Required with ColorMatrix1. */
    val calibrationIlluminant1: Int?,
    val uniqueCameraModel: String,
    val make: String? = null,
    val model: String? = null,
    val software: String? = null,
    val exposureTimeSeconds: Double? = null,
    val iso: Int? = null
) {
    init {
        require(blackLevels.size == 4) { "four CFA black levels are required" }
        require(whiteLevel > blackLevels.max()) { "white level must exceed the black levels" }
        require(asShotNeutral.size == 3 && asShotNeutral.all { it.isFinite() && it > 0 }) { "AsShotNeutral needs three positive values" }
        require(colorMatrix1 != null || forwardMatrix1 != null) { "a DNG needs ColorMatrix1 or ForwardMatrix1; refusing to invent colour calibration" }
        require(colorMatrix1 == null || (colorMatrix1.size == 9 && calibrationIlluminant1 != null)) { "ColorMatrix1 needs 9 values and a CalibrationIlluminant1" }
        require(forwardMatrix1 == null || forwardMatrix1.size == 9) { "ForwardMatrix1 needs 9 values" }
        require(uniqueCameraModel.isNotBlank()) { "UniqueCameraModel is required" }
    }
}

class DngWriteResult(val bytes: ByteArray, val width: Int, val height: Int, val clippedLow: Long, val clippedHigh: Long)

/**
 * Writes a linear, uncompressed, 16 bit CFA DNG (DNG 1.4, little endian, single IFD plus an EXIF IFD) from a normalized
 * Bayer mosaic such as the temporal merge output. Normalized value v maps to `black + v * (white - black)` per CFA
 * position; values outside the sensor range are clipped and counted. NoiseProfile is deliberately not written: the
 * source frames' profile would overstate the noise of a merged image.
 */
object DngWriter {
    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_SRATIONAL = 10

    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    fun write(mosaic: FloatPlane, meta: DngMetadata): DngWriteResult {
        val w = mosaic.width
        val h = mosaic.height
        require(w % 2 == 0 && h % 2 == 0) { "a Bayer mosaic needs even dimensions" }

        // Pixel data
        val pixels = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
        var low = 0L
        var high = 0L
        val white = meta.whiteLevel
        for (y in 0 until h) for (x in 0 until w) {
            val black = meta.blackLevels[(y and 1) * 2 + (x and 1)]
            val dn = black + mosaic.data[y * w + x].toDouble() * (white - black)
            val q = dn.roundToInt()
            val c = when {
                q < 0 -> { low++; 0 }
                q > white -> { high++; white.toInt() }
                else -> q
            }
            pixels.putShort(c.toShort())
        }

        fun s(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
        fun l(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
        fun ascii(t: String) = t.toByteArray(Charsets.US_ASCII) + 0
        fun rationals(values: DoubleArray, denominator: Long) = ByteBuffer.allocate(values.size * 8).order(ByteOrder.LITTLE_ENDIAN).also { b ->
            values.forEach { b.putInt(Math.round(it * denominator).toInt()); b.putInt(denominator.toInt()) }
        }.array()

        val exif = ArrayList<Entry>()
        meta.exposureTimeSeconds?.takeIf { it > 0 && it.isFinite() }?.let { t ->
            val (n, d) = if (t < 1.0) 1L to Math.round(1.0 / t) else Math.round(t * 1000) to 1000L
            exif.add(Entry(33434, TYPE_RATIONAL, 1, l(n) + l(d)))
        }
        meta.iso?.takeIf { it in 1..65535 }?.let { exif.add(Entry(34855, TYPE_SHORT, 1, s(it))) }

        val ifd = ArrayList<Entry>()
        ifd.add(Entry(254, TYPE_LONG, 1, l(0)))
        ifd.add(Entry(256, TYPE_LONG, 1, l(w.toLong())))
        ifd.add(Entry(257, TYPE_LONG, 1, l(h.toLong())))
        ifd.add(Entry(258, TYPE_SHORT, 1, s(16)))
        ifd.add(Entry(259, TYPE_SHORT, 1, s(1)))
        ifd.add(Entry(262, TYPE_SHORT, 1, s(32803)))
        meta.make?.let { ifd.add(Entry(271, TYPE_ASCII, ascii(it).size, ascii(it))) }
        meta.model?.let { ifd.add(Entry(272, TYPE_ASCII, ascii(it).size, ascii(it))) }
        ifd.add(Entry(273, TYPE_LONG, 1, ByteArray(4))) // StripOffsets, patched
        ifd.add(Entry(274, TYPE_SHORT, 1, s(1)))
        ifd.add(Entry(277, TYPE_SHORT, 1, s(1)))
        ifd.add(Entry(278, TYPE_LONG, 1, l(h.toLong())))
        ifd.add(Entry(279, TYPE_LONG, 1, l(pixels.capacity().toLong())))
        ifd.add(Entry(284, TYPE_SHORT, 1, s(1)))
        meta.software?.let { ifd.add(Entry(305, TYPE_ASCII, ascii(it).size, ascii(it))) }
        ifd.add(Entry(33421, TYPE_SHORT, 2, s(2) + s(2)))
        ifd.add(Entry(33422, TYPE_BYTE, 4, ByteArray(4) { meta.cfa.colors[it].toByte() }))
        if (exif.isNotEmpty()) ifd.add(Entry(34665, TYPE_LONG, 1, ByteArray(4))) // patched
        ifd.add(Entry(50706, TYPE_BYTE, 4, byteArrayOf(1, 4, 0, 0)))
        ifd.add(Entry(50707, TYPE_BYTE, 4, byteArrayOf(1, 1, 0, 0)))
        ifd.add(Entry(50708, TYPE_ASCII, ascii(meta.uniqueCameraModel).size, ascii(meta.uniqueCameraModel)))
        ifd.add(Entry(50713, TYPE_SHORT, 2, s(2) + s(2)))
        ifd.add(Entry(50714, TYPE_RATIONAL, 4, rationals(meta.blackLevels, 1000)))
        ifd.add(Entry(50717, TYPE_LONG, 1, l(white.toLong())))
        meta.colorMatrix1?.let { ifd.add(Entry(50721, TYPE_SRATIONAL, 9, rationals(it, 1_000_000))) }
        ifd.add(Entry(50728, TYPE_RATIONAL, 3, rationals(meta.asShotNeutral, 1_000_000)))
        meta.calibrationIlluminant1?.let { ifd.add(Entry(50778, TYPE_SHORT, 1, s(it))) }
        meta.forwardMatrix1?.let { ifd.add(Entry(50964, TYPE_SRATIONAL, 9, rationals(it, 1_000_000))) }
        ifd.sortBy { it.tag }
        exif.sortBy { it.tag }

        fun typeSize(t: Int) = when (t) { TYPE_BYTE, TYPE_ASCII -> 1; TYPE_SHORT -> 2; TYPE_LONG -> 4; else -> 8 }
        fun ifdBytes(list: List<Entry>) = 2 + list.size * 12 + 4
        fun extraBytes(list: List<Entry>) = list.sumOf { val n = it.count * typeSize(it.type); if (n > 4) n + (n and 1) else 0 }

        val ifdOffset = 8
        val exifOffset = ifdOffset + ifdBytes(ifd) + extraBytes(ifd)
        val ifdExtraStart = ifdOffset + ifdBytes(ifd)
        val exifExtraStart = exifOffset + ifdBytes(exif)
        val pixelOffset = (if (exif.isEmpty()) exifOffset else exifExtraStart + extraBytes(exif)).let { it + (it and 1) }

        fun writeIfd(list: List<Entry>, extraStart: Int, out: ByteArrayOutputStream) {
            val extra = ByteArrayOutputStream()
            out.write(s(list.size))
            for (e in list) {
                out.write(s(e.tag)); out.write(s(e.type)); out.write(l(e.count.toLong()))
                val data = when (e.tag) {
                    273 -> l(pixelOffset.toLong())
                    34665 -> l(exifOffset.toLong())
                    else -> e.data
                }
                if (data.size <= 4) { out.write(data); repeat(4 - data.size) { out.write(0) } }
                else { out.write(l((extraStart + extra.size()).toLong())); extra.write(data); if (data.size and 1 == 1) extra.write(0) }
            }
            out.write(l(0))
            out.write(extra.toByteArray())
        }

        val file = ByteArrayOutputStream(pixelOffset + pixels.capacity())
        file.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0)); file.write(l(ifdOffset.toLong()))
        writeIfd(ifd, ifdExtraStart, file)
        if (exif.isNotEmpty()) writeIfd(exif, exifExtraStart, file)
        while (file.size() < pixelOffset) file.write(0)
        file.write(pixels.array())
        return DngWriteResult(file.toByteArray(), w, h, low, high)
    }
}
