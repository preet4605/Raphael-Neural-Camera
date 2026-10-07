package com.neuralcamera.isp.dng

import com.neuralcamera.isp.calibration.LensShadingMap
import com.neuralcamera.isp.raw.RawFrontEnd
import com.neuralcamera.isp.temporal.FloatPlane
import com.neuralcamera.isp.temporal.NoiseModel
import com.neuralcamera.isp.temporal.U16Plane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic opcode lists and mosaics (test fixtures, not device data). */
class DngGainMapTest {

    /** Four per-phase GainMaps as DngCreator lays them out; phase p has gain 1 + p/10 + column/100. */
    private fun opcodeList(w: Int, h: Int, rows: Int = 3, cols: Int = 4, extraOpcode: Boolean = false): ByteArray {
        val b = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN)
        b.putInt(if (extraOpcode) 5 else 4)
        if (extraOpcode) { b.putInt(1); b.putInt(0); b.putInt(1); b.putInt(4); b.putInt(0) } // WarpRectilinear stub
        for (p in 0 until 4) {
            val n = rows * cols
            b.putInt(DngGainMap.OPCODE_GAIN_MAP); b.putInt(0x01030000); b.putInt(0); b.putInt(76 + 4 * n)
            for (v in intArrayOf(p / 2, p % 2, h, w, 0, 1, 2, 2, rows, cols)) b.putInt(v)
            b.putDouble(1.0 / (rows - 1)); b.putDouble(1.0 / (cols - 1)); b.putDouble(0.0); b.putDouble(0.0)
            b.putInt(1)
            for (k in 0 until n) b.putFloat(1f + p / 10f + (k % cols) / 100f)
        }
        return b.array().copyOf(b.position())
    }

    @Test
    fun perPhaseMapsBecomeOneShadingMapInChannelOrder() {
        val notes = mutableListOf<String>()
        val maps = DngGainMap.parseOpcodeList(opcodeList(64, 48, extraOpcode = true), notes)
        assertEquals(4, maps.size)
        assertTrue(notes.any { it.contains("opcode 1 not applied") })
        // GRBG: phase 0 is G_even, 1 is R, 2 is B, 3 is G_odd.
        val m = assertNotNullAnd(DngGainMap.toLensShadingMap(maps, CfaPattern.GRBG, 64, 48, notes))
        assertEquals(1.1f, m.gain(0, 0, 0), 1e-6f)   // R from phase 1
        assertEquals(1.0f, m.gain(1, 0, 0), 1e-6f)   // G_even from phase 0
        assertEquals(1.3f, m.gain(2, 0, 0), 1e-6f)   // G_odd from phase 3
        assertEquals(1.23f, m.gain(3, 3, 2), 1e-6f)  // B from phase 2, last column
    }

    @Test
    fun unsupportedLayoutsAndTruncationAreReportedNotGuessed() {
        val notes = mutableListOf<String>()
        val maps = DngGainMap.parseOpcodeList(opcodeList(64, 48), notes)
        assertNull(DngGainMap.toLensShadingMap(maps.take(3), CfaPattern.RGGB, 64, 48, notes))
        assertNull(DngGainMap.toLensShadingMap(maps, CfaPattern.RGGB, 66, 48, notes)) // map spans a different area
        assertTrue(notes.count { it.contains("not supported") } == 2)
        val truncated = opcodeList(64, 48).copyOf(200)
        assertTrue(DngGainMap.parseOpcodeList(truncated, notes).size < 4)
        assertTrue(notes.any { it.contains("truncated") })
    }

    @Test
    fun rawDefectCorrectionReplacesOnlyOutliersInDn() {
        val w = 32; val h = 32
        val raw = U16Plane(w, h, ShortArray(w * h) { 600 })
        raw.data[10 * w + 11] = 4095.toShort()
        raw.data[20 * w + 20] = 64.toShort()
        val noise = List(4) { NoiseModel(1e-3, 1e-5) }
        val n = RawFrontEnd.correctDefectsRaw(raw, DoubleArray(4) { 64.0 }, 4095.0, noise)
        assertEquals(2, n)
        assertTrue(raw.data.all { it.toInt() == 600 })
    }

    @Test
    fun shadingOnACropUsesTheFullImagePosition() {
        val map = LensShadingMap(2, 2, FloatArray(16) { i -> if ((i / 4) % 2 == 1) 3f else 1f }) // gain 1 on the left, 3 on the right
        val full = FloatPlane(8, 4).also { it.data.fill(1f) }
        RawFrontEnd.applyLensShading(full, CfaPattern.RGGB, map)
        val crop = FloatPlane(4, 4).also { it.data.fill(1f) }
        RawFrontEnd.applyLensShading(crop, CfaPattern.RGGB, map, originX = 4, originY = 0, fullWidth = 8, fullHeight = 4)
        for (y in 0 until 4) for (x in 0 until 4) assertEquals(full.data[y * 8 + 4 + x], crop.data[y * 4 + x], 1e-6f)
    }

    private fun <T> assertNotNullAnd(v: T?): T { assertNotNull(v); return v!! }
}
