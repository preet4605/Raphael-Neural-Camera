package com.neuralcamera.isp

import com.neuralcamera.cameracore.CameraFrame
import com.neuralcamera.isp.temporal.U16Plane

/** Reads a frame's first (luma) plane honouring its row and pixel strides, which are often larger than the width. */
internal object LumaExtractor {

    fun toU16(frame: CameraFrame): U16Plane {
        require(frame.format != "RAW_SENSOR" && frame.format != "RAW10" && frame.format != "RAW12") {
            "The baseline ISP takes 8-bit luma; ${frame.format} frames must go through the Bayer merge"
        }
        val plane = frame.planes.firstOrNull() ?: throw IllegalArgumentException("frame ${frame.frameId} has no planes")
        val w = frame.width
        val h = frame.height
        require(plane.pixelStride >= 1 && plane.rowStride >= (w - 1) * plane.pixelStride + 1) {
            "frame ${frame.frameId}: rowStride ${plane.rowStride} cannot hold $w pixels at pixelStride ${plane.pixelStride}"
        }
        val needed = (h - 1).toLong() * plane.rowStride + (w - 1).toLong() * plane.pixelStride + 1
        require(plane.buffer.size >= needed) { "frame ${frame.frameId}: luma buffer has ${plane.buffer.size} bytes, needs $needed" }
        val data = ShortArray(w * h)
        for (y in 0 until h) {
            val row = y * plane.rowStride
            for (x in 0 until w) data[y * w + x] = (plane.buffer[row + x * plane.pixelStride].toInt() and 0xFF).toShort()
        }
        return U16Plane(w, h, data)
    }
}

/**
 * Chroma of a YUV_420_888 frame: planes 1 (Cb/U) and 2 (Cr/V) are half resolution in both axes. Returns full-resolution
 * Cb and Cr by bilinear interpolation (chroma sited at the centre of each 2x2 luma block), or null when the frame has
 * no usable chroma planes (then the caller stays grayscale rather than guessing colour).
 */
internal object ChromaExtractor {

    class Chroma(val cb: ByteArray, val cr: ByteArray)

    /** Native 4:2:0 chroma: [width] x [height] = ceil(frame / 2), values 0..255. */
    class HalfChroma(val width: Int, val height: Int, val cb: IntArray, val cr: IntArray)

    fun toFullRes(frame: CameraFrame): Chroma? = halfRes(frame)?.let { upsample(it, frame.width, frame.height) }

    fun halfRes(frame: CameraFrame): HalfChroma? {
        if (frame.format != "YUV_420_888" || frame.planes.size < 3) return null
        val cw = (frame.width + 1) / 2
        val ch = (frame.height + 1) / 2
        fun half(index: Int): IntArray? {
            val p = frame.planes[index]
            if (p.pixelStride < 1 || p.rowStride < (cw - 1) * p.pixelStride + 1) return null
            val needed = (ch - 1).toLong() * p.rowStride + (cw - 1).toLong() * p.pixelStride + 1
            if (p.buffer.size < needed) return null
            return IntArray(cw * ch) { p.buffer[(it / cw) * p.rowStride + (it % cw) * p.pixelStride].toInt() and 0xFF }
        }
        val u = half(1) ?: return null
        val v = half(2) ?: return null
        return HalfChroma(cw, ch, u, v)
    }

    fun upsample(c: HalfChroma, w: Int, h: Int): Chroma {
        val cw = c.width
        val ch = c.height
        fun plane(src: IntArray): ByteArray {
            val out = ByteArray(w * h)
            for (y in 0 until h) {
                val fy = ((y - 0.5f) / 2f).coerceIn(0f, (ch - 1).toFloat())
                val y0 = fy.toInt()
                val y1 = minOf(y0 + 1, ch - 1)
                val ay = fy - y0
                for (x in 0 until w) {
                    val fx = ((x - 0.5f) / 2f).coerceIn(0f, (cw - 1).toFloat())
                    val x0 = fx.toInt()
                    val x1 = minOf(x0 + 1, cw - 1)
                    val ax = fx - x0
                    val top = src[y0 * cw + x0] * (1 - ax) + src[y0 * cw + x1] * ax
                    val bot = src[y1 * cw + x0] * (1 - ax) + src[y1 * cw + x1] * ax
                    out[y * w + x] = Math.round(top * (1 - ay) + bot * ay).coerceIn(0, 255).toByte()
                }
            }
            return out
        }
        return Chroma(plane(c.cb), plane(c.cr))
    }
}
