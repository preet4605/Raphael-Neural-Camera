package com.neuralcamera.isp.encode

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Optional EXIF fields written into the JPEG (APP1). Only values the caller actually has should be set. */
data class JpegExif(
    /** EXIF orientation 1..8 (1 = upright). */
    val orientation: Int? = null,
    val exposureTimeSeconds: Double? = null,
    val iso: Int? = null,
    val software: String? = null,
    /** IFD0 ImageDescription, e.g. the provenance summary. Non-ASCII characters are written as '?'. */
    val imageDescription: String? = null
)

/**
 * Baseline sequential JPEG encoder (ITU T.81), pure Kotlin, so output is identical on every platform and testable on a
 * JVM. 8 bit grayscale or RGB (YCbCr 4:4:4, no chroma subsampling), standard quantization tables scaled by quality,
 * standard Huffman tables (Annex K luminance tables for every component, which is valid), JFIF header, optional EXIF.
 * Not optimized for speed: about a second per 10 MP on a modern phone core.
 */
object JpegEncoder {

    private val LUMA_Q = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99
    )
    private val CHROMA_Q = IntArray(64) { 99 }.also {
        val head = intArrayOf(17, 18, 24, 47, 18, 21, 26, 66, 24, 26, 56, 99, 47, 66, 99, 99)
        for (y in 0 until 4) for (x in 0 until 4) it[y * 8 + x] = head[y * 4 + x]
    }

    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63
    )

    private val DC_BITS = intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
    private val DC_VALS = IntArray(12) { it }
    private val AC_BITS = intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
    private val AC_VALS: IntArray = run {
        val head = intArrayOf(
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32,
            0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72, 0x82
        )
        val out = ArrayList<Int>(head.toList())
        out.addAll(0x09..0x0a); out.addAll(0x16..0x1a); out.addAll(0x25..0x2a); out.addAll(0x34..0x3a)
        for (row in intArrayOf(0x40, 0x50, 0x60, 0x70, 0x80)) out.addAll((row + 3)..(row + 0xa)) // 43..4a ... 83..8a
        for (row in intArrayOf(0x90, 0xa0, 0xb0, 0xc0, 0xd0)) out.addAll((row + 2)..(row + 0xa)) // 92..9a ... d2..da
        for (row in intArrayOf(0xe0, 0xf0)) out.addAll((row + 1)..(row + 0xa)) // e1..ea, f1..fa
        out.toIntArray()
    }

    /** Canonical Huffman code for each symbol: (code, length). */
    private class Huffman(bits: IntArray, vals: IntArray) {
        val code = IntArray(256)
        val size = IntArray(256)

        init {
            var c = 0
            var k = 0
            for (len in 1..16) {
                repeat(bits[len - 1]) {
                    code[vals[k]] = c
                    size[vals[k]] = len
                    k++
                    c++
                }
                c = c shl 1
            }
        }
    }

    private class BitWriter(val out: ByteArrayOutputStream) {
        private var acc = 0
        private var n = 0
        fun put(code: Int, len: Int) {
            if (len == 0) return
            acc = (acc shl len) or (code and ((1 shl len) - 1))
            n += len
            while (n >= 8) {
                val b = (acc shr (n - 8)) and 0xFF
                out.write(b)
                if (b == 0xFF) out.write(0)
                n -= 8
            }
            acc = acc and ((1 shl n) - 1)
        }
        fun flush() {
            if (n > 0) put(0x7F, 8 - n) // pad with ones
        }
    }

    private val COS = Array(8) { u -> DoubleArray(8) { x -> cos((2 * x + 1) * u * PI / 16.0) * (if (u == 0) 1 / sqrt(2.0) else 1.0) / 2.0 } }

    /** Forward 8x8 DCT of level-shifted samples (separable, direct form). */
    private fun dct(block: DoubleArray, out: DoubleArray) {
        val tmp = DoubleArray(64)
        for (y in 0 until 8) for (u in 0 until 8) {
            var s = 0.0
            for (x in 0 until 8) s += block[y * 8 + x] * COS[u][x]
            tmp[y * 8 + u] = s
        }
        for (u in 0 until 8) for (v in 0 until 8) {
            var s = 0.0
            for (y in 0 until 8) s += tmp[y * 8 + u] * COS[v][y]
            out[v * 8 + u] = s
        }
    }

    private fun scaledTable(base: IntArray, quality: Int): IntArray {
        val q = quality.coerceIn(1, 100)
        val scale = if (q < 50) 5000 / q else 200 - 2 * q
        return IntArray(64) { ((base[it] * scale + 50) / 100).coerceIn(1, 255) }
    }

    private fun magnitudeBits(v: Int): Pair<Int, Int> {
        var a = if (v < 0) -v else v
        var size = 0
        while (a != 0) { size++; a = a shr 1 }
        val bits = if (v < 0) (v - 1) and ((1 shl size) - 1) else v
        return size to bits
    }

    /** @param gray one byte per pixel */
    fun encodeGray(gray: ByteArray, width: Int, height: Int, quality: Int = 92, exif: JpegExif? = null): ByteArray {
        require(gray.size == width * height) { "gray buffer has ${gray.size} bytes for ${width}x$height" }
        return encode(arrayOf(gray), 1, width, height, quality, exif)
    }

    /** @param rgb interleaved R,G,B bytes */
    /** @param iccProfile embedded as APP2 ICC_PROFILE (e.g. [IccProfile.forOutput] for Display P3 pixels); null = untagged (sRGB by convention) */
    fun encodeRgb(rgb: ByteArray, width: Int, height: Int, quality: Int = 92, exif: JpegExif? = null, iccProfile: ByteArray? = null): ByteArray {
        require(rgb.size == width * height * 3) { "rgb buffer has ${rgb.size} bytes for ${width}x$height" }
        val n = width * height
        val y = ByteArray(n)
        val cb = ByteArray(n)
        val cr = ByteArray(n)
        for (i in 0 until n) {
            val r = rgb[i * 3].toInt() and 255
            val g = rgb[i * 3 + 1].toInt() and 255
            val b = rgb[i * 3 + 2].toInt() and 255
            y[i] = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255).toByte()
            cb[i] = (128 - 0.168736 * r - 0.331264 * g + 0.5 * b).roundToInt().coerceIn(0, 255).toByte()
            cr[i] = (128 + 0.5 * r - 0.418688 * g - 0.081312 * b).roundToInt().coerceIn(0, 255).toByte()
        }
        return encode(arrayOf(y, cb, cr), 3, width, height, quality, exif, iccProfile)
    }

    private fun encode(components: Array<ByteArray>, count: Int, width: Int, height: Int, quality: Int, exif: JpegExif?, icc: ByteArray? = null): ByteArray {
        require(icc == null || icc.size <= 65519) { "ICC profiles larger than one APP2 segment are not supported" }
        require(width in 1..65535 && height in 1..65535) { "image size out of JPEG range" }
        val lumaQ = scaledTable(LUMA_Q, quality)
        val chromaQ = scaledTable(CHROMA_Q, quality)
        val dc = Huffman(DC_BITS, DC_VALS)
        val ac = Huffman(AC_BITS, AC_VALS)
        val out = ByteArrayOutputStream(width * height / 4 + 1024)

        fun marker(m: Int) { out.write(0xFF); out.write(m) }
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 255) }

        marker(0xD8)
        // JFIF APP0
        marker(0xE0); u16(16); out.write("JFIF".toByteArray()); out.write(0); out.write(1); out.write(1); out.write(0); u16(1); u16(1); out.write(0); out.write(0)
        if (exif != null) {
            val tiff = ExifBlock.build(exif)
            if (tiff != null) { marker(0xE1); u16(2 + 6 + tiff.size); out.write("Exif".toByteArray()); out.write(0); out.write(0); out.write(tiff) }
        }
        if (icc != null) {
            // APP2 "ICC_PROFILE\0", chunk 1 of 1.
            marker(0xE2); u16(2 + 12 + 2 + icc.size); out.write("ICC_PROFILE".toByteArray()); out.write(0); out.write(1); out.write(1); out.write(icc)
        }
        for (t in 0 until (if (count == 1) 1 else 2)) {
            marker(0xDB); u16(67); out.write(t)
            val table = if (t == 0) lumaQ else chromaQ
            for (i in 0 until 64) out.write(table[ZIGZAG[i]])
        }
        marker(0xC0); u16(8 + 3 * count); out.write(8); u16(height); u16(width); out.write(count)
        for (c in 0 until count) { out.write(c + 1); out.write(0x11); out.write(if (c == 0) 0 else 1) }
        fun dht(tableClass: Int, bits: IntArray, vals: IntArray) {
            marker(0xC4); u16(2 + 1 + 16 + vals.size); out.write(tableClass shl 4)
            bits.forEach { out.write(it) }
            vals.forEach { out.write(it) }
        }
        dht(0, DC_BITS, DC_VALS)
        dht(1, AC_BITS, AC_VALS)
        marker(0xDA); u16(6 + 2 * count); out.write(count)
        for (c in 0 until count) { out.write(c + 1); out.write(0x00) }
        out.write(0); out.write(63); out.write(0)

        val bw = BitWriter(out)
        val block = DoubleArray(64)
        val coef = DoubleArray(64)
        val prevDc = IntArray(count)
        val blocksX = (width + 7) / 8
        val blocksY = (height + 7) / 8
        for (by in 0 until blocksY) for (bx in 0 until blocksX) {
            for (c in 0 until count) {
                val plane = components[c]
                for (y in 0 until 8) {
                    val sy = minOf(by * 8 + y, height - 1)
                    for (x in 0 until 8) {
                        val sx = minOf(bx * 8 + x, width - 1)
                        block[y * 8 + x] = (plane[sy * width + sx].toInt() and 255) - 128.0
                    }
                }
                dct(block, coef)
                val q = if (c == 0) lumaQ else chromaQ
                val quant = IntArray(64) { i -> (coef[ZIGZAG[i]] / q[ZIGZAG[i]]).roundToInt() }
                val diff = quant[0] - prevDc[c]
                prevDc[c] = quant[0]
                val (dcSize, dcBits) = magnitudeBits(diff)
                bw.put(dc.code[dcSize], dc.size[dcSize])
                bw.put(dcBits, dcSize)
                var run = 0
                for (i in 1 until 64) {
                    val v = quant[i]
                    if (v == 0) { run++; continue }
                    while (run > 15) { bw.put(ac.code[0xF0], ac.size[0xF0]); run -= 16 }
                    val (size, bits) = magnitudeBits(v)
                    val sym = (run shl 4) or size
                    bw.put(ac.code[sym], ac.size[sym])
                    bw.put(bits, size)
                    run = 0
                }
                if (run > 0) bw.put(ac.code[0x00], ac.size[0x00])
            }
        }
        bw.flush()
        marker(0xD9)
        return out.toByteArray()
    }
}

/** Minimal EXIF TIFF block (little endian): IFD0 {ImageDescription, Orientation, Software, ExifIFD} + Exif IFD {ExposureTime, ISO}. */
internal object ExifBlock {
    fun build(e: JpegExif): ByteArray? {
        class Entry(val tag: Int, val type: Int, val count: Int, val value: ByteArray)
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

        val exifEntries = ArrayList<Entry>()
        e.exposureTimeSeconds?.takeIf { it > 0 && it.isFinite() }?.let { t ->
            // Rational with a numerator/denominator that preserves short exposures: 1/N when t < 1, else N/1000.
            val (n, d) = if (t < 1.0) 1L to Math.round(1.0 / t) else Math.round(t * 1000) to 1000L
            exifEntries.add(Entry(0x829A, 5, 1, le32(n) + le32(d)))
        }
        e.iso?.takeIf { it in 1..65535 }?.let { exifEntries.add(Entry(0x8827, 3, 1, le16(it))) }
        val ifd0 = ArrayList<Entry>()
        e.orientation?.takeIf { it in 1..8 }?.let { ifd0.add(Entry(0x0112, 3, 1, le16(it))) }
        e.software?.let { s -> val b = s.toByteArray(Charsets.US_ASCII) + 0; ifd0.add(Entry(0x0131, 2, b.size, b)) }
        e.imageDescription?.let { s ->
            val b = s.map { if (it.code in 0x20..0x7E) it else '?' }.joinToString("").toByteArray(Charsets.US_ASCII) + 0
            ifd0.add(Entry(0x010E, 2, b.size, b))
        }
        if (ifd0.isEmpty() && exifEntries.isEmpty()) return null
        if (exifEntries.isNotEmpty()) ifd0.add(Entry(0x8769, 4, 1, ByteArray(4))) // patched below
        ifd0.sortBy { it.tag }

        val headerSize = 8
        fun ifdSize(list: List<Entry>) = 2 + list.size * 12 + 4
        val ifd0Offset = headerSize
        var cursor = ifd0Offset + ifdSize(ifd0)
        val exifIfdOffset = cursor
        if (exifEntries.isNotEmpty()) cursor += ifdSize(exifEntries)
        val extra = java.io.ByteArrayOutputStream()
        fun write(list: List<Entry>, out: java.io.ByteArrayOutputStream) {
            out.write(le16(list.size))
            for (en in list) {
                out.write(le16(en.tag)); out.write(le16(en.type)); out.write(le32(en.count.toLong()))
                if (en.tag == 0x8769) out.write(le32(exifIfdOffset.toLong()))
                else if (en.value.size <= 4) { out.write(en.value); repeat(4 - en.value.size) { out.write(0) } }
                else { out.write(le32((cursor + extra.size()).toLong())); extra.write(en.value); if (extra.size() % 2 == 1) extra.write(0) }
            }
            out.write(le32(0))
        }
        val body = java.io.ByteArrayOutputStream()
        body.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0)); body.write(le32(ifd0Offset.toLong()))
        write(ifd0, body)
        if (exifEntries.isNotEmpty()) write(exifEntries, body)
        body.write(extra.toByteArray())
        return body.toByteArray()
    }
}
