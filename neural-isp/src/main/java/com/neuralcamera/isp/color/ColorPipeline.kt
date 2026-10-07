package com.neuralcamera.isp.color

import com.neuralcamera.isp.dng.CfaPattern
import com.neuralcamera.isp.temporal.FloatPlane

class ColorResult(val width: Int, val height: Int, val srgb8: ByteArray, val linearSrgb: RgbImage, val transformSource: ColorTransform.Source)

/**
 * Normalized linear Bayer mosaic (for example the temporal merge output) to a display image:
 * demosaic, camera-to-sRGB colour transform (white balance included), tone mapping, sRGB encoding.
 * Baseline colour processing only: no lens-shading, denoise, sharpening, local tone mapping or gamut handling beyond a clamp.
 */
object ColorPipeline {
    fun render(mosaic: FloatPlane, cfa: CfaPattern, transform: ColorTransform, tone: ToneParams = ToneParams()): ColorResult {
        val rgb = Demosaic.run(mosaic, cfa)
        val d = rgb.data
        var i = 0
        while (i < d.size) {
            transform.apply(d[i], d[i + 1], d[i + 2], d, i)
            i += 3
        }
        return ColorResult(rgb.width, rgb.height, ToneMapper.toSrgb8(rgb, tone), rgb, transform.source)
    }
}
