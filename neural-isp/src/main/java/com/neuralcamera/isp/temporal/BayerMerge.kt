package com.neuralcamera.isp.temporal

/**
 * Sensor black levels in CFA position order (top-left, top-right, bottom-left, bottom-right of each 2x2 cell), the shared
 * white level, and the frame's radiometric gain relative to the reference frame (reference = 1).
 */
class BayerRadiometry(val blackLevels: DoubleArray, val whiteLevel: Double, val gain: Double = 1.0) {
    init {
        require(blackLevels.size == 4) { "four CFA black levels are required" }
    }

    fun forPosition(position: Int) = Radiometry(blackLevels[position], whiteLevel, gain)
}

class BayerFrame(val mosaic: U16Plane, val radiometry: BayerRadiometry) {
    init {
        require(mosaic.width % 2 == 0 && mosaic.height % 2 == 0) { "a Bayer mosaic needs even dimensions" }
    }
}

class BayerMergeResult(
    /** Merged mosaic on the reference's normalized scale, same CFA layout as the input. */
    val mosaic: FloatPlane,
    /** Per alternate frame, averaged over the four colour planes. */
    val frameStats: List<FrameMergeStats>
)

/**
 * Temporal merge in the RAW Bayer domain. The mosaic is split into its four colour planes (half resolution). Motion is
 * estimated once per alternate frame on a luma-like proxy (the sum of the four planes) and the same motion field merges
 * every plane, so the CFA structure is preserved exactly: no output sample is ever built from another colour's sensor
 * sites. Noise is modelled per colour plane. Demosaicing is a later stage.
 */
object BayerTemporalMerge {

    fun run(
        frames: List<BayerFrame>,
        referenceIndex: Int,
        noise: List<NoiseModel>,
        mergeParams: MergeParams = MergeParams(),
        alignParams: AlignParams = AlignParams(),
        threads: Int = TemporalMerge.defaultThreads(),
        /** Per frame index (the reference's entry is ignored), in mosaic pixels; null entries align unseeded. */
        seeds: List<AlignmentSeed?>? = null
    ): BayerMergeResult {
        require(seeds == null || seeds.size == frames.size) { "one seed slot per frame" }
        require(frames.isNotEmpty()) { "no frames" }
        require(referenceIndex in frames.indices) { "reference index out of range" }
        require(noise.size == 4) { "one noise model per CFA position is required" }
        val w = frames[0].mosaic.width
        val h = frames[0].mosaic.height
        frames.forEach { require(it.mosaic.width == w && it.mosaic.height == h) { "all frames must share one size" } }

        val planes = frames.map { splitPlanes(it) }        // [frame][position]
        val pw = w / 2
        val ph = h / 2
        val altIndexes = frames.indices.filter { it != referenceIndex }

        val refProxy = lumaProxy(planes[referenceIndex])
        val aligner = TileAligner(alignParams)
        val fieldSlots = arrayOfNulls<MotionField>(altIndexes.size)
        // Alignment runs on the half-resolution plane proxy, so mosaic-pixel seeds are halved.
        Parallel.run(altIndexes.size, threads) { index, _ ->
            fieldSlots[index] = aligner.align(refProxy, lumaProxy(planes[altIndexes[index]]), seeds?.get(altIndexes[index])?.scaled(0.5f))
        }
        val fields = fieldSlots.map { it!! }

        val out = FloatPlane(w, h)
        val statsPerPlane = ArrayList<List<FrameMergeStats>>()
        for (position in 0 until 4) {
            val ref = planes[referenceIndex][position]
            val alts = altIndexes.map { planes[it][position] }
            val merged = TemporalMerger.merge(ref, alts, fields, noise[position], mergeParams, threads)
            statsPerPlane.add(merged.frameStats)
            val px = position % 2
            val py = position / 2
            for (y in 0 until ph) for (x in 0 until pw) {
                out.data[(2 * y + py) * w + (2 * x + px)] = merged.output.data[y * pw + x]
            }
        }
        val stats = altIndexes.indices.map { ai ->
            FrameMergeStats(
                altIndex = ai,
                meanWeight = statsPerPlane.map { it[ai].meanWeight }.average(),
                tilesRejected = statsPerPlane.sumOf { it[ai].tilesRejected } / 4,
                tilesTotal = statsPerPlane.sumOf { it[ai].tilesTotal } / 4,
                meanAbsResidual = statsPerPlane.map { it[ai].meanAbsResidual }.filter { !it.isNaN() }.average(),
                tileWeights = TileWeights.meanOf(statsPerPlane.map { it[ai].tileWeights!! })
            )
        }
        return BayerMergeResult(out, stats)
    }

    /** Four half-resolution planes in CFA position order (index = row parity * 2 + column parity). */
    internal fun splitPlanes(frame: BayerFrame): List<Frame> {
        val w = frame.mosaic.width
        val h = frame.mosaic.height
        val pw = w / 2
        val ph = h / 2
        return List(4) { position ->
            val px = position % 2
            val py = position / 2
            val data = ShortArray(pw * ph)
            for (y in 0 until ph) for (x in 0 until pw) data[y * pw + x] = frame.mosaic.data[(2 * y + py) * w + (2 * x + px)]
            Frame(U16Plane(pw, ph, data), frame.radiometry.forPosition(position))
        }
    }

    /** Sum of the four colour planes on the common scale; NaN if any contributing sample is clipped. */
    private fun lumaProxy(planes: List<Frame>): FloatPlane {
        val proxies = planes.map { AlignmentProxy.of(it) }
        val out = FloatPlane(proxies[0].width, proxies[0].height)
        for (i in out.data.indices) {
            var s = 0f
            for (p in proxies) s += p.data[i] // NaN propagates
            out.data[i] = s
        }
        return out
    }
}
