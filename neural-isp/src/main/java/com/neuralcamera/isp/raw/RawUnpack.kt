package com.neuralcamera.isp.raw

import com.neuralcamera.isp.temporal.U16Plane

/**
 * Unpacks Android RAW buffers to 16-bit samples. Layouts follow android.graphics.ImageFormat:
 * - RAW10: 4 pixels in 5 bytes. Bytes 0..3 hold bits 9..2 of pixels 0..3; byte 4 holds bits 1..0 of pixel 0 in bits
 *   1..0, pixel 1 in bits 3..2, pixel 2 in bits 5..4, pixel 3 in bits 7..6.
 * - RAW12: 2 pixels in 3 bytes. Bytes 0..1 hold bits 11..4 of pixels 0..1; byte 2 holds bits 3..0 of pixel 0 in bits
 *   3..0 and of pixel 1 in bits 7..4.
 * - RAW_SENSOR (RAW16): little-endian 16-bit samples.
 * [rowStride] is in bytes and may include padding. Which of these the OnePlus 15 delivers at full resolution is part of
 * Gate 1: NOT_TESTED.
 */
object RawUnpack {
    enum class Packing(val bitsPerPixel: Int) { RAW10(10), RAW12(12), RAW16(16) }

    fun minRowBytes(packing: Packing, width: Int): Int = when (packing) {
        Packing.RAW10 -> width * 5 / 4
        Packing.RAW12 -> width * 3 / 2
        Packing.RAW16 -> width * 2
    }

    fun unpack(data: ByteArray, width: Int, height: Int, rowStride: Int, packing: Packing): U16Plane {
        require(width > 0 && height > 0) { "empty image" }
        when (packing) {
            Packing.RAW10 -> require(width % 4 == 0) { "RAW10 width must be a multiple of 4" }
            Packing.RAW12 -> require(width % 2 == 0) { "RAW12 width must be a multiple of 2" }
            Packing.RAW16 -> Unit
        }
        val minRow = minRowBytes(packing, width)
        require(rowStride >= minRow) { "rowStride $rowStride < $minRow bytes needed for $width px of $packing" }
        require(data.size >= rowStride.toLong() * (height - 1) + minRow) { "buffer too small for $width x $height $packing" }
        val out = ShortArray(width * height)
        for (y in 0 until height) {
            val row = y * rowStride
            val o = y * width
            when (packing) {
                Packing.RAW10 -> {
                    var x = 0; var b = row
                    while (x < width) {
                        val lsb = data[b + 4].toInt() and 0xFF
                        for (k in 0 until 4) out[o + x + k] = (((data[b + k].toInt() and 0xFF) shl 2) or ((lsb shr (2 * k)) and 0x3)).toShort()
                        x += 4; b += 5
                    }
                }
                Packing.RAW12 -> {
                    var x = 0; var b = row
                    while (x < width) {
                        val lsb = data[b + 2].toInt() and 0xFF
                        out[o + x] = (((data[b].toInt() and 0xFF) shl 4) or (lsb and 0xF)).toShort()
                        out[o + x + 1] = (((data[b + 1].toInt() and 0xFF) shl 4) or (lsb shr 4)).toShort()
                        x += 2; b += 3
                    }
                }
                Packing.RAW16 -> for (x in 0 until width) {
                    val b = row + 2 * x
                    out[o + x] = ((data[b].toInt() and 0xFF) or ((data[b + 1].toInt() and 0xFF) shl 8)).toShort()
                }
            }
        }
        return U16Plane(width, height, out)
    }

    /** Inverse of [unpack] for tests and tooling (no row padding). */
    fun pack(plane: U16Plane, packing: Packing): ByteArray {
        val w = plane.width
        val rowBytes = minRowBytes(packing, w)
        val out = ByteArray(rowBytes * plane.height)
        for (y in 0 until plane.height) {
            val row = y * rowBytes
            when (packing) {
                Packing.RAW10 -> for (x in 0 until w step 4) {
                    val b = row + x / 4 * 5
                    var lsb = 0
                    for (k in 0 until 4) {
                        val v = plane.at(x + k, y)
                        out[b + k] = (v shr 2).toByte(); lsb = lsb or ((v and 0x3) shl (2 * k))
                    }
                    out[b + 4] = lsb.toByte()
                }
                Packing.RAW12 -> for (x in 0 until w step 2) {
                    val b = row + x / 2 * 3
                    val v0 = plane.at(x, y); val v1 = plane.at(x + 1, y)
                    out[b] = (v0 shr 4).toByte(); out[b + 1] = (v1 shr 4).toByte()
                    out[b + 2] = ((v0 and 0xF) or ((v1 and 0xF) shl 4)).toByte()
                }
                Packing.RAW16 -> for (x in 0 until w) {
                    val v = plane.at(x, y)
                    out[row + 2 * x] = v.toByte(); out[row + 2 * x + 1] = (v shr 8).toByte()
                }
            }
        }
        return out
    }
}
