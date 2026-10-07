import com.neuralcamera.benchmarks.Json
import com.neuralcamera.isp.color.ColorPipeline
import com.neuralcamera.isp.dng.DngPreview
import com.neuralcamera.isp.dng.DngRawImage
import com.neuralcamera.isp.dng.DngReader
import com.neuralcamera.isp.encode.DngMetadata
import com.neuralcamera.isp.encode.DngWriter
import com.neuralcamera.isp.encode.JpegEncoder
import com.neuralcamera.isp.raw.RawFrontEnd
import com.neuralcamera.isp.temporal.BayerFrame
import com.neuralcamera.isp.temporal.BayerRadiometry
import com.neuralcamera.isp.temporal.BayerTemporalMerge
import com.neuralcamera.isp.temporal.FloatPlane
import com.neuralcamera.isp.temporal.FrameAnalysis
import com.neuralcamera.isp.temporal.NoiseModel
import com.neuralcamera.isp.temporal.TemporalMerge
import com.neuralcamera.isp.temporal.U16Plane
import java.awt.image.BufferedImage
import java.io.File
import java.io.PrintStream
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * Offline RAW burst merge for DNG frames. Reads frame_*.dng, merges them in the Bayer domain with the tile-aligned,
 * noise-aware, motion-robust temporal merge, and writes a linear merged mosaic (16-bit PGM), quick-look previews and a
 * JSON report. Per-frame exposure/ISO come from gate1_report.json when present (so exposure bracketing and AE drift are
 * accounted for), else from the DNG's own EXIF tags, else the frames are assumed equally exposed.
 *
 * RAW front end: hot/dead pixels are fixed in every frame before the merge (needs a noise model), and the lens shading
 * gain map the DNG carries (OpcodeList2, written by DngCreator when the capture enabled the shading map) is applied to
 * the merged mosaic and to the reference used for comparison. Either is skipped, and the report says so, when its
 * input is missing or it is turned off with --no-defects / --no-shading.
 *
 * This is an evaluation tool: previews are a bilinear demosaic with white balance and an sRGB curve, not a finished ISP.
 */
object BurstMergeTool {

    private class Options(
        val input: File,
        val output: File,
        val reference: Int?,
        val crop: IntArray?,
        val threads: Int,
        val maxFrames: Int?,
        val noiseOverride: NoiseModel?,
        val defects: Boolean,
        val shading: Boolean
    )

    private fun parse(args: List<String>): Options {
        fun value(name: String) = args.indexOf(name).let { if (it >= 0 && it + 1 < args.size) args[it + 1] else null }
        val input = File(value("--input") ?: throw IllegalArgumentException("--input <directory with frame_*.dng> is required"))
        val output = File(value("--output") ?: File(input, "merged").path)
        val reference = value("--reference")?.takeIf { it != "auto" }?.toInt()
        val crop = value("--crop")?.split(",")?.map { it.trim().toInt() }?.toIntArray()
        require(crop == null || (crop.size == 4 && crop.all { it % 2 == 0 && it >= 0 } && crop[2] > 0 && crop[3] > 0)) {
            "--crop x,y,width,height needs four even, non-negative numbers (width and height > 0)"
        }
        val noise = value("--noise")?.split(",")?.map { it.trim().toDouble() }?.let {
            require(it.size == 2) { "--noise needs shot,read" }
            NoiseModel(it[0], it[1])
        }
        return Options(
            input, output, reference, crop, value("--threads")?.toInt() ?: TemporalMerge.defaultThreads(),
            value("--max-frames")?.toInt(), noise, "--no-defects" !in args, "--no-shading" !in args
        )
    }

    fun run(args: List<String>, out: PrintStream): Int {
        val o = try {
            parse(args)
        } catch (e: Exception) {
            out.println("error: ${e.message}")
            out.println("usage: --input <dir> [--output <dir>] [--reference auto|N] [--crop x,y,w,h] [--threads N] [--max-frames N] [--noise shot,read] [--no-defects] [--no-shading]")
            return 2
        }
        val files = (o.input.listFiles { f -> f.isFile && f.name.endsWith(".dng", ignoreCase = true) } ?: emptyArray())
            .sortedBy { it.name }.let { list -> o.maxFrames?.let { list.take(it) } ?: list }
        if (files.size < 2) {
            out.println("error: need at least two DNG frames in ${o.input}, found ${files.size}")
            return 2
        }
        val notes = mutableListOf<String>()
        val t0 = System.nanoTime()

        var images = files.map { DngReader.read(it) }
        val first = images[0]
        require(images.all { it.width == first.width && it.height == first.height && it.cfa == first.cfa && it.whiteLevel == first.whiteLevel }) {
            "all frames must share size, CFA and white level"
        }
        val shadingMaps = images.map { it.lensShading }
        if (o.crop != null) {
            val (x, y, w, h) = o.crop.toList()
            require(x + w <= first.width && y + h <= first.height) { "--crop lies outside the ${first.width}x${first.height} image" }
            images = images.map { img ->
                val data = ShortArray(w * h)
                for (row in 0 until h) System.arraycopy(img.mosaic.data, (y + row) * img.width + x, data, row * w, w)
                DngRawImage(w, h, img.cfa, img.blackLevels, img.whiteLevel, img.noise, img.asShotNeutral, img.exposureTimeSeconds, img.iso, U16Plane(w, h, data), img.notes, img.colorMatrix1, img.forwardMatrix1, img.calibrationIlluminant1, img.uniqueCameraModel)
            }
        }
        val width = images[0].width
        val height = images[0].height

        // Reference: requested, else the sharpest frame by Laplacian variance of the first green plane.
        val greenPosition = first.cfa.colors.indexOfFirst { it == 1 }
        val greenPlanes = images.map { img ->
            val data = ShortArray((width / 2) * (height / 2))
            val px = greenPosition % 2
            val py = greenPosition / 2
            for (yy in 0 until height / 2) for (xx in 0 until width / 2) data[yy * (width / 2) + xx] = img.mosaic.data[(2 * yy + py) * width + 2 * xx + px]
            U16Plane(width / 2, height / 2, data)
        }
        val refIndex = o.reference ?: FrameAnalysis.sharpestIndex(greenPlanes)
        require(refIndex in images.indices) { "--reference $refIndex is out of range 0..${images.size - 1}" }

        // Gains from gate1_report.json (exposure x ISO), else EXIF, else 1.
        val perFrame = readGate1Exposures(File(o.input, "gate1_report.json"))
        fun exposureOf(i: Int): Double? =
            perFrame[files[i].name]?.let { (e, iso) -> e * iso } ?: images[i].let { img -> if (img.exposureTimeSeconds != null && img.iso != null) img.exposureTimeSeconds!! * 1e9 * img.iso!! else null }
        val refExposure = exposureOf(refIndex)
        val gains = images.indices.map { i -> val e = exposureOf(i); if (e != null && refExposure != null) e / refExposure else 1.0 }
        if (refExposure == null) notes.add("no exposure/ISO found for the reference frame: all frames assumed equally exposed")

        val noise = images[refIndex].noise ?: o.noiseOverride?.let { n -> List(4) { n } }
            ?: run {
                out.println("error: the DNGs carry no NoiseProfile; pass --noise shot,read (normalized units)")
                return 2
            }
        val noiseSource = if (images[refIndex].noise != null) "DNG NoiseProfile" else "--noise override"

        // Front end, per frame and before the merge: table-free (dynamic) defect correction in DN.
        val defectCounts = if (o.defects) images.map { img ->
            RawFrontEnd.correctDefectsRaw(img.mosaic, img.blackLevels, img.whiteLevel, img.noise ?: noise)
        } else null
        val frontEnd = linkedMapOf<String, Any?>(
            "order" to "BLACK_LEVEL, DEFECT_CORRECTION per frame -> TEMPORAL_MERGE -> LENS_SHADING",
            "defectCorrection" to (defectCounts?.let { mapOf("method" to "dynamic, ${RawFrontEnd.DEFECT_SIGMA} sigma vs same-colour median", "pixelsPerFrame" to it) }
                ?: "skipped: --no-defects")
        )

        val frames = images.mapIndexed { i, img -> BayerFrame(img.mosaic, BayerRadiometry(img.blackLevels, img.whiteLevel, gains[i])) }
        out.println("merging ${frames.size} frames ${width}x$height ${first.cfa}, reference=${files[refIndex].name}, threads=${o.threads}")
        val tMerge = System.nanoTime()
        val result = BayerTemporalMerge.run(frames, refIndex, noise, threads = o.threads)
        val mergeSeconds = (System.nanoTime() - tMerge) / 1e9

        o.output.mkdirs()
        val ref = images[refIndex]
        val refNorm = DngPreview.normalize(ref.mosaic, ref.blackLevels, ref.whiteLevel)
        val shadingMap = shadingMaps[refIndex]
        frontEnd["lensShading"] = when {
            !o.shading -> "skipped: --no-shading"
            shadingMap == null -> "skipped: the reference DNG has no usable GainMap (see dngNotes)"
            else -> {
                // Same gains on the merged mosaic and on the reference, so the comparisons below stay like for like.
                for (plane in listOf(result.mosaic, refNorm)) {
                    RawFrontEnd.applyLensShading(plane, ref.cfa, shadingMap, o.crop?.get(0) ?: 0, o.crop?.get(1) ?: 0, first.width, first.height)
                }
                "applied: DNG GainMap ${shadingMap.columns}x${shadingMap.rows} (reference frame), after the merge"
            }
        }
        writePgm16(File(o.output, "merged.pgm"), result.mosaic)
        writePng(File(o.output, "reference_preview.png"), DngPreview.render(refNorm, ref.cfa, ref.asShotNeutral), width, height)
        writePng(File(o.output, "merged_preview.png"), DngPreview.render(result.mosaic, ref.cfa, ref.asShotNeutral), width, height)

        val transform = ref.toColorTransform()
        val refColor = ColorPipeline.render(refNorm, ref.cfa, transform)
        val mergedColor = ColorPipeline.render(result.mosaic, ref.cfa, transform)
        writePng8(File(o.output, "reference_color.png"), refColor.srgb8, width, height)
        writePng8(File(o.output, "merged_color.png"), mergedColor.srgb8, width, height)

        // Real encoders: a linear merged DNG (needs the source's colour calibration) and a JPEG of the colour render.
        val dngStatus: Any = try {
            val neutral = ref.asShotNeutral
            if (neutral == null || (ref.colorMatrix1 == null && ref.forwardMatrix1 == null) || ref.uniqueCameraModel == null ||
                (ref.colorMatrix1 != null && ref.calibrationIlluminant1 == null)
            ) {
                "skipped: the source DNG lacks AsShotNeutral, a colour matrix (with illuminant) or UniqueCameraModel"
            } else {
                val meta = DngMetadata(
                    ref.cfa, ref.blackLevels, ref.whiteLevel, neutral, ref.colorMatrix1, ref.forwardMatrix1, ref.calibrationIlluminant1,
                    ref.uniqueCameraModel, software = "Raphael burst_merge", exposureTimeSeconds = ref.exposureTimeSeconds, iso = ref.iso
                )
                val dng = DngWriter.write(result.mosaic, meta)
                File(o.output, "merged.dng").writeBytes(dng.bytes)
                mapOf("file" to "merged.dng", "clippedLow" to dng.clippedLow, "clippedHigh" to dng.clippedHigh)
            }
        } catch (e: Exception) {
            "failed: ${e.message}"
        }
        File(o.output, "merged_color.jpg").writeBytes(JpegEncoder.encodeRgb(mergedColor.srgb8, width, height, quality = 92))

        fun greenSigma(norm: FloatPlane): Double {
            val pw = width / 2
            val ph = height / 2
            val g = FloatPlane(pw, ph)
            val px = greenPosition % 2
            val py = greenPosition / 2
            for (yy in 0 until ph) for (xx in 0 until pw) g.data[yy * pw + xx] = norm.data[(2 * yy + py) * width + 2 * xx + px]
            return FrameAnalysis.noiseSigma(g)
        }
        val sigmaRef = greenSigma(refNorm)
        val sigmaMerged = greenSigma(result.mosaic)

        val report = linkedMapOf<String, Any?>(
            "schema" to "raphael.burstmerge/1",
            "note" to "Evaluation output. The noise figures are a global estimate on the first green plane (structure biases both the same way); this is not Gate 3 evidence.",
            "frames" to files.mapIndexed { i, f ->
                mapOf("file" to f.name, "gainVsReference" to gains[i], "isReference" to (i == refIndex), "dngNotes" to images[i].notes)
            },
            "size" to mapOf("width" to width, "height" to height, "crop" to o.crop?.toList()),
            "cfa" to first.cfa.name,
            "frontEnd" to frontEnd,
            "colorTransform" to transform.source.name,
            "mergedDng" to dngStatus,
            "whiteLevel" to first.whiteLevel,
            "blackLevels" to ref.blackLevels.toList(),
            "noise" to mapOf("source" to noiseSource, "perCfaPosition" to noise.map { mapOf("shot" to it.shot, "read" to it.read) }),
            "mergeStats" to result.frameStats.map {
                mapOf("altIndex" to it.altIndex, "meanWeight" to it.meanWeight, "tilesRejected" to it.tilesRejected, "tilesTotal" to it.tilesTotal, "meanAbsResidual" to it.meanAbsResidual)
            },
            "noiseSigmaGreen" to mapOf("reference" to sigmaRef, "merged" to sigmaMerged, "ratio" to if (sigmaRef > 0) sigmaMerged / sigmaRef else null),
            "seconds" to mapOf("total" to (System.nanoTime() - t0) / 1e9, "merge" to mergeSeconds),
            "notes" to notes
        )
        File(o.output, "merge_report.json").writeText(Json.stringify(report))
        out.println("wrote ${o.output}: merged.pgm, reference_preview.png, merged_preview.png, reference_color.png, merged_color.png, merged_color.jpg, merged.dng if calibrated (colour: ${transform.source}), merge_report.json")
        out.println("green-plane noise sigma: reference %.5f -> merged %.5f (ratio %.2f); merge took %.1f s".format(sigmaRef, sigmaMerged, if (sigmaRef > 0) sigmaMerged / sigmaRef else Double.NaN, mergeSeconds))
        result.frameStats.forEach { out.println("  alt ${it.altIndex}: meanWeight %.2f, tiles rejected %d/%d".format(it.meanWeight, it.tilesRejected, it.tilesTotal)) }
        return 0
    }

    /** dngFile -> (exposureTimeNs, iso) from a Gate 1 report, if one sits next to the DNGs. */
    private fun readGate1Exposures(file: File): Map<String, Pair<Double, Double>> {
        if (!file.isFile) return emptyMap()
        return try {
            val frames = ((Json.parse(file.readText()) as? Map<*, *>)?.get("frames") as? List<*>) ?: return emptyMap()
            frames.mapNotNull { f ->
                val m = f as? Map<*, *> ?: return@mapNotNull null
                val name = m["dngFile"] as? String ?: return@mapNotNull null
                val e = (m["exposureTimeNs"] as? Number)?.toDouble() ?: return@mapNotNull null
                val iso = (m["iso"] as? Number)?.toDouble() ?: return@mapNotNull null
                name to (e to iso)
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writePgm16(file: File, plane: FloatPlane) {
        file.outputStream().buffered().use { os ->
            os.write("P5\n${plane.width} ${plane.height}\n65535\n".toByteArray())
            val row = ByteArray(plane.width * 2)
            for (y in 0 until plane.height) {
                for (x in 0 until plane.width) {
                    val v = Math.round(plane.data[y * plane.width + x].coerceIn(0f, 1f) * 65535f)
                    row[2 * x] = (v shr 8).toByte()
                    row[2 * x + 1] = v.toByte()
                }
                os.write(row)
            }
        }
    }

    /** Writes an 8-bit sRGB PNG (interleaved RGB bytes), subsampled so the longest side is at most 2048 px. */
    private fun writePng8(file: File, rgb: ByteArray, width: Int, height: Int) {
        val step = maxOf(1, Math.ceil(maxOf(width, height) / 2048.0).toInt())
        val w = width / step
        val h = height / step
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val i = ((y * step) * width + x * step) * 3
            image.setRGB(x, y, ((rgb[i].toInt() and 255) shl 16) or ((rgb[i + 1].toInt() and 255) shl 8) or (rgb[i + 2].toInt() and 255))
        }
        ImageIO.write(image, "png", file)
    }

    /** Writes an sRGB PNG, subsampled so the longest side is at most 2048 px. */
    private fun writePng(file: File, rgb: FloatArray, width: Int, height: Int) {
        val step = maxOf(1, Math.ceil(maxOf(width, height) / 2048.0).toInt())
        val w = width / step
        val h = height / step
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val i = ((y * step) * width + x * step) * 3
            val r = Math.round(rgb[i] * 255f)
            val g = Math.round(rgb[i + 1] * 255f)
            val b = Math.round(rgb[i + 2] * 255f)
            image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
        ImageIO.write(image, "png", file)
    }
}

fun main(args: Array<String>) {
    exitProcess(BurstMergeTool.run(args.toList(), System.out))
}
