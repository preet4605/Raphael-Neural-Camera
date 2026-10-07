package com.neuralcamera.isp

import com.neuralcamera.isp.temporal.AlignmentProxy
import com.neuralcamera.isp.temporal.Frame
import com.neuralcamera.isp.temporal.FrameAnalysis
import com.neuralcamera.isp.temporal.FrameMergeStats
import com.neuralcamera.isp.temporal.MotionField
import com.neuralcamera.isp.temporal.NoiseModel
import com.neuralcamera.isp.temporal.Radiometry
import com.neuralcamera.isp.temporal.TemporalMerger
import com.neuralcamera.isp.temporal.TileGrid
import com.neuralcamera.isp.temporal.U16Plane

/**
 * Temporal merge of 4:2:0 chroma along the luma motion. Chroma has too little texture to align on its own, so each
 * chroma tile takes the motion of the luma tile with the same centre (luma tile 2k for chroma tile k), halved. The
 * merge's own per-pixel difference test still rejects chroma that disagrees (a coloured object that moved), with a
 * noise level estimated from the reference's chroma. Values at or above 98.5 % of full scale count as clipped and are
 * not merged.
 */
internal object ChromaMerge {
    private val EIGHT_BIT = Radiometry(blackLevel = 0.0, whiteLevel = 255.0)
    private const val MIN_SIGMA = 0.5 / 255.0

    class Result(val chroma: ChromaExtractor.HalfChroma, val cbStats: List<FrameMergeStats>, val crStats: List<FrameMergeStats>)

    /** Luma motion resampled onto the chroma tile grid, in chroma pixels. */
    fun chromaField(luma: MotionField, chromaWidth: Int, chromaHeight: Int): MotionField {
        val tx = TileGrid.tilesFor(chromaWidth)
        val ty = TileGrid.tilesFor(chromaHeight)
        val dx = FloatArray(tx * ty); val dy = FloatArray(tx * ty); val cost = FloatArray(tx * ty)
        for (ky in 0 until ty) for (kx in 0 until tx) {
            val li = luma.index((2 * kx).coerceAtMost(luma.tilesX - 1), (2 * ky).coerceAtMost(luma.tilesY - 1))
            val i = ky * tx + kx
            dx[i] = luma.dx[li] / 2f; dy[i] = luma.dy[li] / 2f; cost[i] = luma.cost[li]
        }
        return MotionField(tx, ty, dx, dy, cost)
    }

    /** [lumaFields] are the alternate frames' fields in frame order with the reference skipped, as TemporalMerge returns them. */
    fun merge(chroma: List<ChromaExtractor.HalfChroma>, referenceIndex: Int, lumaFields: List<MotionField>, threads: Int): Result {
        val ref = chroma[referenceIndex]
        require(chroma.all { it.width == ref.width && it.height == ref.height }) { "chroma planes differ in size" }
        require(lumaFields.size == chroma.size - 1) { "one luma field per alternate frame" }
        val fields = lumaFields.map { chromaField(it, ref.width, ref.height) }
        fun mergePlane(pick: (ChromaExtractor.HalfChroma) -> IntArray): Pair<IntArray, List<FrameMergeStats>> {
            val frames = chroma.map { Frame(U16Plane.fromInts(ref.width, ref.height, pick(it)), EIGHT_BIT) }
            val sigma = maxOf(FrameAnalysis.noiseSigma(AlignmentProxy.of(frames[referenceIndex])), MIN_SIGMA)
            val alts = frames.filterIndexed { i, _ -> i != referenceIndex }
            val m = TemporalMerger.merge(frames[referenceIndex], alts, fields, NoiseModel(shot = 0.0, read = sigma * sigma), threads = threads)
            return IntArray(ref.width * ref.height) { Math.round(m.output.data[it].coerceIn(0f, 1f) * 255f) } to m.frameStats
        }
        val (cb, cbStats) = mergePlane { it.cb }
        val (cr, crStats) = mergePlane { it.cr }
        return Result(ChromaExtractor.HalfChroma(ref.width, ref.height, cb, cr), cbStats, crStats)
    }
}
