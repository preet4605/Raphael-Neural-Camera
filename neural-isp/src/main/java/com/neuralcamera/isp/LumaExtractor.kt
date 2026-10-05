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
