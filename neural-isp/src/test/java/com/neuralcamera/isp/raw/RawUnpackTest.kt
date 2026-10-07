package com.neuralcamera.isp.raw

import com.neuralcamera.isp.temporal.U16Plane
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class RawUnpackTest {

    @Test
    fun raw10BitLayoutMatchesAndroidSpec() {
        // Pixels 0..3 = 0x3FF, 0x001, 0x200, 0x155.
        val bytes = byteArrayOf(0xFF.toByte(), 0x00, 0x80.toByte(), 0x55, 0b01_00_01_11.toByte())
        val p = RawUnpack.unpack(bytes, 4, 1, 5, RawUnpack.Packing.RAW10)
        assertEquals(listOf(0x3FF, 0x001, 0x200, 0x155), (0 until 4).map { p.at(it, 0) })
    }

    @Test
    fun raw12BitLayoutMatchesAndroidSpec() {
        // Pixels 0..1 = 0xABC, 0x123.
        val bytes = byteArrayOf(0xAB.toByte(), 0x12, 0x3C)
        val p = RawUnpack.unpack(bytes, 2, 1, 3, RawUnpack.Packing.RAW12)
        assertEquals(0xABC, p.at(0, 0)); assertEquals(0x123, p.at(1, 0))
    }

    @Test
    fun roundTripsAllPackingsWithRowPadding() {
        val rnd = Random(7)
        for ((packing, max) in listOf(RawUnpack.Packing.RAW10 to 1023, RawUnpack.Packing.RAW12 to 4095, RawUnpack.Packing.RAW16 to 65535)) {
            val w = 8; val h = 3
            val src = U16Plane.fromInts(w, h, IntArray(w * h) { rnd.nextInt(max + 1) })
            val tight = RawUnpack.pack(src, packing)
            val rowBytes = RawUnpack.minRowBytes(packing, w)
            val stride = rowBytes + 6
            val padded = ByteArray(stride * h)
            for (y in 0 until h) System.arraycopy(tight, y * rowBytes, padded, y * stride, rowBytes)
            val back = RawUnpack.unpack(padded, w, h, stride, packing)
            assertArrayEquals("$packing", src.data, back.data)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooSmallBuffer() { RawUnpack.unpack(ByteArray(4), 4, 1, 5, RawUnpack.Packing.RAW10) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortRowStride() { RawUnpack.unpack(ByteArray(100), 8, 2, 8, RawUnpack.Packing.RAW12) }
}
