package com.neuralcamera.isp.dng

import com.neuralcamera.isp.temporal.FloatPlane
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteOrder

/** Reader tests on small synthetic DNG-shaped files (fixtures, not camera output). */
class DngReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun mosaic(w: Int, h: Int) = ShortArray(w * h) { ((it * 37 + 11) % 4000).toShort() }

    private fun write(spec: TestDng.Spec) = tmp.newFile().also { TestDng.write(it, spec) }

    @Test
    fun readsAllTagsInBothByteOrders() {
        for (order in listOf(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
            val data = mosaic(8, 6)
            val file = write(
                TestDng.Spec(
                    8, 6, data, order = order, cfaColors = intArrayOf(1, 0, 2, 1), blacks = doubleArrayOf(60.0, 62.0, 64.0, 66.0),
                    white = 4000, noiseProfile = doubleArrayOf(0.001, 0.0001, 0.002, 0.0002, 0.003, 0.0003),
                    neutral = doubleArrayOf(0.5, 1.0, 0.6), exposureSeconds = 0.01, iso = 200
                )
            )

            val img = DngReader.read(file)

            assertEquals("$order", CfaPattern.GRBG, img.cfa)
            assertEquals(8, img.width)
            assertEquals(6, img.height)
            assertArrayEquals(doubleArrayOf(60.0, 62.0, 64.0, 66.0), img.blackLevels, 1e-5)
            assertEquals(4000.0, img.whiteLevel, 0.0)
            assertArrayEquals(data, img.mosaic.data)
            assertEquals(0.01, img.exposureTimeSeconds!!, 1e-6)
            assertEquals(200, img.iso)
            assertArrayEquals(doubleArrayOf(0.5, 1.0, 0.6), img.asShotNeutral!!, 1e-5)
            // 3-plane profile maps to GRBG positions: G, R, B, G
            val n = img.noise!!
            assertEquals(listOf(0.002, 0.001, 0.003, 0.002), n.map { it.shot })
            assertEquals(0.0002, n[0].read, 1e-12)
        }
    }

    @Test
    fun fourPairNoiseProfileMapsStraightToCfaPositionsAndOnePairBroadcasts() {
        val four = DngReader.read(write(TestDng.Spec(4, 4, mosaic(4, 4), noiseProfile = doubleArrayOf(1e-3, 1e-4, 2e-3, 2e-4, 3e-3, 3e-4, 4e-3, 4e-4))))
        assertEquals(listOf(1e-3, 2e-3, 3e-3, 4e-3), four.noise!!.map { it.shot })
        val one = DngReader.read(write(TestDng.Spec(4, 4, mosaic(4, 4), noiseProfile = doubleArrayOf(5e-3, 5e-4))))
        assertEquals(List(4) { 5e-3 }, one.noise!!.map { it.shot })
        val none = DngReader.read(write(TestDng.Spec(4, 4, mosaic(4, 4))))
        assertNull(none.noise)
        assertTrue(none.notes.any { it.contains("NoiseProfile") })
    }

    @Test
    fun activeAreaCropMovesTheCfaPhaseAndRotatesPerPositionArrays() {
        val w = 8
        val h = 8
        val data = ShortArray(w * h) { (it % w * 10 + it / w).toShort() } // value encodes (x, y)
        val file = write(
            TestDng.Spec(
                w, h, data, cfaColors = intArrayOf(0, 1, 1, 2), blacks = doubleArrayOf(10.0, 20.0, 30.0, 40.0),
                noiseProfile = doubleArrayOf(1e-3, 1e-4, 2e-3, 2e-4, 3e-3, 3e-4), activeArea = longArrayOf(1, 1, 7, 7)
            )
        )

        val img = DngReader.read(file)

        assertEquals(6, img.width)
        assertEquals(6, img.height)
        assertEquals("RGGB shifted by (1,1) becomes BGGR", CfaPattern.BGGR, img.cfa)
        assertArrayEquals(doubleArrayOf(40.0, 30.0, 20.0, 10.0), img.blackLevels, 1e-9)
        assertEquals(data[1 * w + 1], img.mosaic.data[0])
        assertEquals(data[6 * w + 6], img.mosaic.data[5 * 6 + 5])
        assertEquals(3e-3, img.noise!![0].shot, 1e-12) // blue position first now
        assertTrue(img.notes.any { it.contains("ActiveArea") })
    }

    @Test
    fun blackLevelLayoutsAndMissingValuesAreHandled() {
        val single = DngReader.read(write(TestDng.Spec(4, 4, mosaic(4, 4), blacks = doubleArrayOf(88.0), blackRepeat = null)))
        assertArrayEquals(DoubleArray(4) { 88.0 }, single.blackLevels, 1e-9)
        val rows = DngReader.read(write(TestDng.Spec(4, 4, mosaic(4, 4), blacks = doubleArrayOf(1.0, 2.0), blackRepeat = intArrayOf(2, 1))))
        assertArrayEquals(doubleArrayOf(1.0, 1.0, 2.0, 2.0), rows.blackLevels, 1e-9)
        val missing = DngReader.read(write(TestDng.Spec(4, 4, mosaic(4, 4), blacks = null, white = null)))
        assertArrayEquals(DoubleArray(4), missing.blackLevels, 0.0)
        assertEquals(65535.0, missing.whiteLevel, 0.0)
        assertTrue(missing.notes.size >= 2)
    }

    @Test
    fun unsupportedOrBrokenFilesAreRejectedWithClearErrors() {
        assertThrows(TiffFormatException::class.java) { DngReader.read(tmp.newFile().also { it.writeText("this is not a tiff") }) }
        assertThrows(TiffFormatException::class.java) { DngReader.read(write(TestDng.Spec(4, 4, mosaic(4, 4), photometric = 2))) }
        assertThrows(TiffFormatException::class.java) { DngReader.read(write(TestDng.Spec(4, 4, mosaic(4, 4), compression = 7))) }
        assertThrows(TiffFormatException::class.java) { DngReader.read(write(TestDng.Spec(4, 4, mosaic(4, 4), bits = 12))) }
        assertThrows(TiffFormatException::class.java) { DngReader.read(write(TestDng.Spec(4, 4, mosaic(4, 4), cfaColors = null))) }
        assertThrows(TiffFormatException::class.java) { DngReader.read(write(TestDng.Spec(4, 4, mosaic(4, 4), cfaColors = intArrayOf(0, 0, 0, 0)))) }
        val truncated = write(TestDng.Spec(8, 8, mosaic(8, 8)))
        truncated.writeBytes(truncated.readBytes().copyOf(truncated.length().toInt() - 40))
        assertThrows(Exception::class.java) { DngReader.read(truncated) }
    }

    @Test
    fun bayerFrameCarriesTheFilesRadiometry() {
        val img = DngReader.read(write(TestDng.Spec(4, 4, mosaic(4, 4), blacks = doubleArrayOf(1.0, 2.0, 3.0, 4.0), white = 1000)))
        val frame = img.toBayerFrame(gain = 2.0)
        assertEquals(2.0, frame.radiometry.gain, 0.0)
        assertEquals(3.0, frame.radiometry.blackLevels[2], 0.0)
        assertEquals(1000.0, frame.radiometry.whiteLevel, 0.0)
        assertNotNull(frame.mosaic)
    }

    @Test
    fun previewDemosaicsFlatColourPlanesExactly() {
        val w = 8
        val h = 8
        val cfa = CfaPattern.RGGB
        val values = floatArrayOf(0.8f, 0.5f, 0.2f) // flat R, G, B
        val plane = FloatPlane(w, h, FloatArray(w * h) { values[cfa.colors[(it / w % 2) * 2 + it % w % 2]] })

        val rgb = DngPreview.render(plane, cfa, whiteBalance = null)

        fun srgb(v: Double) = if (v <= 0.0031308) 12.92 * v else 1.055 * Math.pow(v, 1 / 2.4) - 0.055
        for (y in 1 until h - 1) for (x in 1 until w - 1) for (c in 0 until 3) {
            assertEquals(srgb(values[c].toDouble()), rgb[(y * w + x) * 3 + c].toDouble(), 1e-5)
        }
        // White balance gains the red channel up and the blue down: neutral (0.5, 1.0, 0.25) => R x2, B x4.
        val wb = DngPreview.render(plane, cfa, doubleArrayOf(0.5, 1.0, 0.25))
        assertEquals(srgb(1.0), wb[(3 * w + 3) * 3 + 0].toDouble(), 1e-5) // 0.8 * 2 clipped to 1
        assertEquals(srgb(0.8), wb[(3 * w + 3) * 3 + 2].toDouble(), 1e-5)  // 0.2 * 4
    }

    @Test
    fun normalizeUsesPerPositionBlackLevels() {
        // Each value sits exactly halfway between its own position's black level and the white level (110).
        val plane = com.neuralcamera.isp.temporal.U16Plane(2, 2, shortArrayOf(60, 65, 70, 75))
        val n = DngPreview.normalize(plane, doubleArrayOf(10.0, 20.0, 30.0, 40.0), 110.0)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), n.data, 1e-6f)
    }
}
