package com.neuralcamera.isp.color

import com.neuralcamera.isp.dng.CfaPattern
import com.neuralcamera.isp.temporal.FloatPlane

class ColorResult(
    val width: Int, val height: Int,
    /** 8-bit interleaved RGB in [output] primaries, sRGB transfer curve. */
    val rgb8: ByteArray,
    /** Linear RGB in [output] primaries, before tone mapping. */
    val linear: RgbImage,
    val transformSource: ColorTransform.Source,
    val output: OutputSpace = OutputSpace.SRGB
)

/**
 * Normalized linear Bayer mosaic (for example the temporal merge output) to a display image:
 * demosaic, camera-to-output colour transform (sRGB or Display P3, white balance included), tone mapping, sRGB-curve encoding.
 * Baseline colour processing only: no lens-shading, denoise, sharpening or local tone mapping. Out-of-gamut colours are
 * mapped by [GamutMapping] and highlights desaturate in the shoulder ([ToneParams]).
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
        return ColorResult(rgb.width, rgb.height, ToneMapper.toSrgb8(rgb, tone, transform.output), rgb, transform.source, transform.output)
    }
}
