package com.neuralcamera.isp.encode

import com.neuralcamera.isp.dng.CfaPattern
import com.neuralcamera.isp.dng.DngReader
import com.neuralcamera.isp.temporal.FloatPlane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin

/** Round-trip checks of the pure-Kotlin encoders against independent decoders (JDK ImageIO, our DngReader). */
class EncodersTest {

    private fun scene(x: Int, y: Int, c: Int) = (128 + 90 * sin(x * 0.07 + c) * sin(y * 0.05 - c)).toInt().coerceIn(0, 255)

    private fun psnr(a: IntArray, b: IntArray): Double {
        var se = 0.0
        for (i in a.indices) { val d = (a[i] - b[i]).toDouble(); se += d * d }
        return if (se == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / (se / a.size))
    }

    @Test
    fun grayJpegDecodesWithNonMultipleOfEightSize() {
        val w = 83
        val h = 61
        val gray = ByteArray(w * h) { scene(it % w, it / w, 0).toByte() }
        val jpeg = JpegEncoder.encodeGray(gray, w, h, quality = 95)
        assertEquals(0xFF, jpeg[0].toInt() and 255); assertEquals(0xD8, jpeg[1].toInt() and 255)
        val img = checkNotNull(JdkDecodedImage.read(jpeg))
        assertEquals(w, img.width); assertEquals(h, img.height)
        val decoded = IntArray(w * h) { img.sample(it % w, it / w, 0) }
        val p = psnr(IntArray(w * h) { gray[it].toInt() and 255 }, decoded)
        assertTrue("PSNR $p", p > 38.0)
    }

    @Test
    fun rgbJpegDecodesWithCorrectColours() {
        val w = 64
        val h = 48
        val rgb = ByteArray(w * h * 3) { scene((it / 3) % w, (it / 3) / w, it % 3).toByte() }
        val jpeg = JpegEncoder.encodeRgb(rgb, w, h, quality = 95)
        val img = checkNotNull(JdkDecodedImage.read(jpeg))
        val a = IntArray(w * h * 3) { rgb[it].toInt() and 255 }
        val b = IntArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val p = img.rgb(x, y)
            b[(y * w + x) * 3] = (p shr 16) and 255; b[(y * w + x) * 3 + 1] = (p shr 8) and 255; b[(y * w + x) * 3 + 2] = p and 255
        }
        val p = psnr(a, b)
        assertTrue("PSNR $p", p > 33.0)
    }

    @Test
    fun qualityTradesSizeForFidelity() {
        val w = 128
        val h = 96
        val gray = ByteArray(w * h) { scene(it % w, it / w, 1).toByte() }
        assertTrue(JpegEncoder.encodeGray(gray, w, h, 30).size < JpegEncoder.encodeGray(gray, w, h, 95).size)
    }

    @Test
    fun exifFieldsAreReadableByAnIndependentParser() {
        val gray = ByteArray(16 * 16) { 100 }
        val jpeg = JpegEncoder.encodeGray(gray, 16, 16, exif = JpegExif(orientation = 6, exposureTimeSeconds = 1.0 / 120, iso = 400, software = "Raphael"))
        assertNotNull(JdkDecodedImage.read(jpeg))
        // Locate APP1 and parse the TIFF block with the same TIFF reader used for DNGs.
        var i = 2
        while (i < jpeg.size) {
            val marker = jpeg[i + 1].toInt() and 255
            val len = ((jpeg[i + 2].toInt() and 255) shl 8) or (jpeg[i + 3].toInt() and 255)
            if (marker == 0xE1) {
                val tiff = jpeg.copyOfRange(i + 4 + 6, i + 2 + len)
                val f = File.createTempFile("exif", ".tif").apply { deleteOnExit() }
                f.writeBytes(tiff)
                com.neuralcamera.isp.dng.TiffFile.open(f).use { t ->
                    assertEquals(6L, t.ifd0.longs(0x0112)!![0])
                    val exif = t.ifds.first { it.entry(0x829A) != null }
                    assertEquals(1.0 / 120, exif.doubles(0x829A)!![0], 1e-4)
                    assertEquals(400L, exif.longs(0x8827)!![0])
                }
                return
            }
            if (marker == 0xDA) break
            i += 2 + len
        }
        throw AssertionError("no EXIF APP1 found")
    }

    @Test
    fun dngRoundTripsThroughOurReaderAndRejectsUncalibratedInput() {
        val w = 32
        val h = 24
        val mosaic = FloatPlane(w, h)
        for (y in 0 until h) for (x in 0 until w) mosaic.data[y * w + x] = (0.1 + 0.8 * ((x + y) % 16) / 15.0).toFloat()
        val blacks = doubleArrayOf(64.0, 65.0, 66.0, 64.5)
        val meta = DngMetadata(
            CfaPattern.GBRG, blacks, 1023.0, doubleArrayOf(0.5, 1.0, 0.6),
            colorMatrix1 = doubleArrayOf(1.1, -0.2, 0.0, -0.3, 1.2, 0.1, 0.0, 0.05, 0.9), forwardMatrix1 = null, calibrationIlluminant1 = 21,
            uniqueCameraModel = "Test Camera", make = "T", model = "Cam", software = "Raphael", exposureTimeSeconds = 0.01, iso = 200
        )
        val out = DngWriter.write(mosaic, meta)
        assertEquals(0L, out.clippedLow + out.clippedHigh)
        val f = File.createTempFile("writer", ".dng").apply { deleteOnExit() }
        f.writeBytes(out.bytes)
        val r = DngReader.read(f)
        assertEquals(w, r.width); assertEquals(h, r.height)
        assertEquals(CfaPattern.GBRG, r.cfa)
        assertEquals(1023.0, r.whiteLevel, 0.0)
        for (i in 0 until 4) assertEquals(blacks[i], r.blackLevels[i], 1e-3)
        assertEquals(21, r.calibrationIlluminant1)
        assertEquals(0.01, r.exposureTimeSeconds!!, 1e-4); assertEquals(200, r.iso)
        assertEquals(0.6, r.asShotNeutral!![2], 1e-6)
        assertEquals(1.2, r.colorMatrix1!![4], 1e-6)
        assertTrue(r.noise == null) // merged output must not carry a source NoiseProfile
        for (y in 0 until h) for (x in 0 until w) {
            val black = blacks[(y and 1) * 2 + (x and 1)]
            val expected = Math.round(black + mosaic.data[y * w + x] * (1023.0 - black)).toInt()
            assertEquals(expected, r.mosaic.at(x, y))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DngMetadata(CfaPattern.RGGB, blacks, 1023.0, doubleArrayOf(1.0, 1.0, 1.0), null, null, null, "x")
        }
    }

    @Test
    fun dngCountsClippedSamples() {
        val m = FloatPlane(4, 4).also { it.data.fill(0.5f); it.data[0] = 1.5f; it.data[1] = -0.5f; it.data[2] = -0.7f }
        val meta = DngMetadata(CfaPattern.RGGB, DoubleArray(4) { 10.0 }, 1000.0, doubleArrayOf(1.0, 1.0, 1.0), null,
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), null, "x")
        val out = DngWriter.write(m, meta)
        assertEquals(1L, out.clippedHigh)
        assertEquals(2L, out.clippedLow)
    }
}
