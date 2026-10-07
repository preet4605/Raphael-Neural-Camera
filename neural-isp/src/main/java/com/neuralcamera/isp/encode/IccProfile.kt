package com.neuralcamera.isp.encode

import com.neuralcamera.isp.color.OutputSpace
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * ICC v4.3 display profiles (matrix/TRC) for the [OutputSpace]s: D50 PCS, Bradford chromatic adaptation from D65
 * ('chad'), the D50-adapted primaries as rXYZ/gXYZ/bXYZ and the sRGB curve as a parametric curve. Built from the same
 * matrices the colour pipeline uses, so the tag says exactly what the pixels are.
 */
object IccProfile {

    fun forOutput(space: OutputSpace): ByteArray {
        val toXyz = space.toXyzD50.m
        val chad = OutputSpace.bradford(OutputSpace.xyToXyz(OutputSpace.D65_XY[0], OutputSpace.D65_XY[1]), OutputSpace.D50_XYZ).m
        val trc = para()
        val tags = listOf(
            "desc" to mluc(space.label),
            "cprt" to mluc("No copyright, use freely"),
            // v4 display profiles: media white = PCS illuminant, encoded exactly as in the header.
            "wtpt" to ByteBuffer.allocate(20).put("XYZ ".toByteArray()).putInt(0).putInt(0x0000F6D6).putInt(0x00010000).putInt(0x0000D32D).array(),
            "chad" to sf32(chad),
            "rXYZ" to xyz(toXyz[0], toXyz[3], toXyz[6]),
            "gXYZ" to xyz(toXyz[1], toXyz[4], toXyz[7]),
            "bXYZ" to xyz(toXyz[2], toXyz[5], toXyz[8]),
            "rTRC" to trc, "gTRC" to trc, "bTRC" to trc
        )
        // Lay out the tag data after the header and tag table; identical data (the three TRCs) is stored once.
        val tableEnd = 128 + 4 + 12 * tags.size
        val data = ByteArrayOutputStream()
        val offsets = java.util.IdentityHashMap<ByteArray, Int>() // the three TRC tags share one array instance
        val entries = tags.map { (sig, bytes) ->
            val offset = offsets.getOrPut(bytes) {
                val o = tableEnd + data.size()
                data.write(bytes)
                while (data.size() % 4 != 0) data.write(0)
                o
            }
            Triple(sig, offset, bytes.size)
        }
        val size = tableEnd + data.size()
        val b = ByteBuffer.allocate(size) // big-endian
        b.putInt(size)
        b.putInt(0)                                 // preferred CMM: none
        b.putInt(0x04300000)                        // version 4.3
        b.put("mntr".toByteArray()); b.put("RGB ".toByteArray()); b.put("XYZ ".toByteArray())
        for (v in intArrayOf(2026, 1, 1, 0, 0, 0)) b.putShort(v.toShort()) // fixed date: reproducible bytes
        b.put("acsp".toByteArray())
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0) // platform, flags, manufacturer, model
        b.putLong(0)                                // attributes
        b.putInt(0)                                 // rendering intent: perceptual
        b.putInt(0x0000F6D6); b.putInt(0x00010000); b.putInt(0x0000D32D) // PCS illuminant D50, the spec's exact encoding
        b.putInt(0)                                 // creator
        b.put(ByteArray(16))                        // profile ID (optional, zero)
        b.put(ByteArray(28))                        // reserved
        b.putInt(entries.size)
        for ((sig, offset, length) in entries) { b.put(sig.toByteArray()); b.putInt(offset); b.putInt(length) }
        b.put(data.toByteArray())
        return b.array()
    }

    private fun s15f16(v: Double) = (v * 65536.0).roundToInt()

    private fun xyz(x: Double, y: Double, z: Double) = ByteBuffer.allocate(20).put("XYZ ".toByteArray()).putInt(0)
        .putInt(s15f16(x)).putInt(s15f16(y)).putInt(s15f16(z)).array()

    private fun sf32(m: DoubleArray) = ByteBuffer.allocate(8 + 36).put("sf32".toByteArray()).putInt(0)
        .apply { m.forEach { putInt(s15f16(it)) } }.array()

    /** sRGB curve: Y = ((X + 0.055) / 1.055)^2.4 for X >= 0.04045, else X / 12.92 (function type 3). */
    private fun para() = ByteBuffer.allocate(12 + 20).put("para".toByteArray()).putInt(0).putShort(3).putShort(0)
        .putInt(s15f16(2.4)).putInt(s15f16(1 / 1.055)).putInt(s15f16(0.055 / 1.055)).putInt(s15f16(1 / 12.92)).putInt(s15f16(0.04045)).array()

    private fun mluc(text: String): ByteArray {
        val utf16 = text.toByteArray(Charsets.UTF_16BE)
        return ByteBuffer.allocate(28 + utf16.size).put("mluc".toByteArray()).putInt(0).putInt(1).putInt(12)
            .put("enUS".toByteArray()).putInt(utf16.size).putInt(28).put(utf16).array()
    }
}
