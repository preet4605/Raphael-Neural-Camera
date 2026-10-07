package com.neuralcamera.isp.dng

import com.neuralcamera.isp.calibration.LensShadingMap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One DNG GainMap opcode (opcode id 9). Coordinates are relative to the image after the ActiveArea crop. */
class GainMapOpcode(
    val top: Int, val left: Int, val bottom: Int, val right: Int,
    val plane: Int, val planes: Int, val rowPitch: Int, val colPitch: Int,
    val pointsV: Int, val pointsH: Int,
    val spacingV: Double, val spacingH: Double, val originV: Double, val originH: Double,
    val mapPlanes: Int,
    /** pointsV x pointsH x mapPlanes gains, row-major with planes innermost. */
    val gains: FloatArray
)

/**
 * Reads GainMap opcodes from a DNG OpcodeList (always big-endian, whatever the TIFF byte order) and turns the
 * per-Bayer-phase maps that Android's DngCreator writes from STATISTICS_LENS_SHADING_CORRECTION_MAP back into a
 * [LensShadingMap]. Layouts other than four full-grid, one-plane, pitch-2 maps are reported and not used.
 */
object DngGainMap {
    const val OPCODE_GAIN_MAP = 9
    private const val HEADER_BYTES = 76

    fun parseOpcodeList(bytes: ByteArray, notes: MutableList<String>): List<GainMapOpcode> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (bytes.size < 4) return emptyList()
        val count = b.int
        val out = ArrayList<GainMapOpcode>()
        repeat(count) {
            if (b.remaining() < 16) { notes += "OpcodeList truncated"; return out }
            val id = b.int; b.int /* dngVersion */; b.int /* flags */
            val size = b.int
            if (size < 0 || size > b.remaining()) { notes += "OpcodeList entry $id claims $size bytes; truncated"; return out }
            val end = b.position() + size
            if (id == OPCODE_GAIN_MAP && size >= HEADER_BYTES) {
                val top = b.int; val left = b.int; val bottom = b.int; val right = b.int
                val plane = b.int; val planes = b.int; val rowPitch = b.int; val colPitch = b.int
                val pv = b.int; val ph = b.int
                val sv = b.double; val sh = b.double; val ov = b.double; val oh = b.double
                val mp = b.int
                val n = pv.toLong() * ph * mp
                if (pv < 1 || ph < 1 || mp < 1 || n * 4 != (size - HEADER_BYTES).toLong()) {
                    notes += "GainMap with ${pv}x$ph x $mp points does not match its $size bytes; ignored"
                } else {
                    val gains = FloatArray(n.toInt()) { b.float }
                    out += GainMapOpcode(top, left, bottom, right, plane, planes, rowPitch, colPitch, pv, ph, sv, sh, ov, oh, mp, gains)
                }
            } else if (id != OPCODE_GAIN_MAP) {
                notes += "OpcodeList2 opcode $id not applied"
            }
            b.position(end)
        }
        return out
    }

    /** Four per-phase maps on one grid spanning the image -> a shading map; null with a note for anything else. */
    fun toLensShadingMap(maps: List<GainMapOpcode>, cfa: CfaPattern, width: Int, height: Int, notes: MutableList<String>): LensShadingMap? {
        if (maps.isEmpty()) return null
        val first = maps[0]
        val phases = maps.map { (it.top and 1) * 2 + (it.left and 1) }
        val tol = 1e-6
        val supported = maps.size == 4 && phases.toSet().size == 4 && maps.all {
            it.planes == 1 && it.mapPlanes == 1 && it.rowPitch == 2 && it.colPitch == 2 &&
                it.pointsV == first.pointsV && it.pointsH == first.pointsH && it.pointsV >= 2 && it.pointsH >= 2 &&
                it.top <= 1 && it.left <= 1 && it.bottom == height && it.right == width &&
                Math.abs(it.originV) < tol && Math.abs(it.originH) < tol &&
                Math.abs(it.spacingV * (it.pointsV - 1) - 1.0) < 1e-3 && Math.abs(it.spacingH * (it.pointsH - 1) - 1.0) < 1e-3
        }
        if (!supported) {
            notes += "GainMap layout not supported (${maps.size} maps; need 4 per-phase full-image maps); lens shading not applied"
            return null
        }
        val cols = first.pointsH; val rows = first.pointsV
        val gains = FloatArray(cols * rows * 4)
        maps.forEachIndexed { i, m ->
            val channel = LensShadingMap.channelOf(cfa, phases[i])
            for (k in 0 until cols * rows) gains[k * 4 + channel] = m.gains[k]
        }
        return try {
            LensShadingMap(cols, rows, gains)
        } catch (e: IllegalArgumentException) {
            notes += "GainMap rejected: ${e.message}"
            null
        }
    }
}
