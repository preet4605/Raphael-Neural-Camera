package com.neuralcamera.isp.temporal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

data class MergeParams(
    /** Residual sub-pixel alignment error (px) the difference test tolerates, scaled by the local gradient. */
    val alignTolerancePx: Double = 0.15,
    /** A pixel whose normalized squared difference reaches this is rejected; below it the weight falls off smoothly. */
    val rejectT: Double = 12.0,
    /** A tile whose mean normalized squared difference exceeds this contributes nothing from that frame. */
    val tileRejectMeanT: Double = 16.0
)

/** What the merge did with one alternate frame; input for the quality engine. */
data class FrameMergeStats(
    /** Index among the alternate frames (reference excluded). */
    val altIndex: Int,
    /** Mean robust weight over comparable pixels (1 = fully merged, 0 = fully rejected). */
    val meanWeight: Double,
    val tilesRejected: Int,
    val tilesTotal: Int,
    /** Mean absolute difference to the reference after alignment, in the reference's normalized units. */
    val meanAbsResidual: Double
)

class MergeResult(
    /** Merged scene-referred linear values on the reference's normalized scale (can exceed 1 when brighter detail is recovered from shorter exposures). */
    val output: FloatPlane,
    val frameStats: List<FrameMergeStats>
)

/** Per-worker scratch buffers for one tile. */
private class TileScratch {
    val r = FloatArray(TileGrid.TILE * TileGrid.TILE)
    val grad = FloatArray(TileGrid.TILE * TileGrid.TILE)
    val a = FloatArray(TileGrid.TILE * TileGrid.TILE)
    val d = FloatArray(TileGrid.TILE * TileGrid.TILE)
    val tStat = FloatArray(TileGrid.TILE * TileGrid.TILE)
    val rob = FloatArray(TileGrid.TILE * TileGrid.TILE)
    val ivAlt = FloatArray(TileGrid.TILE * TileGrid.TILE)
    val colTaps = FloatArray(4)
    val rowTaps = FloatArray(4)
}

/** Per-worker statistics, reduced after the parallel phases. */
private class MergeAccumulators(alts: Int) {
    val sumWeight = DoubleArray(alts)
    val sumWindow = DoubleArray(alts)
    val sumResidual = DoubleArray(alts)
    val residualCount = LongArray(alts)
    val tilesRejected = IntArray(alts)
    val tilesConsidered = IntArray(alts)
}

/**
 * Motion-robust, noise-aware, exposure-aware temporal merge on the overlapping tile grid.
 *
 * For each tile and alternate frame the aligned samples are compared with the reference; the comparison statistic is the
 * 3x3-smoothed difference normalized by the expected noise (Poisson-Gaussian, in the common exposure scale) plus a
 * tolerance proportional to the local gradient for residual misalignment. Pixels that disagree (moving objects,
 * occlusions, misalignment) are rejected; clipped samples carry no weight; the rest are merged by inverse noise variance,
 * so longer exposures count more and clipped highlights are filled from shorter ones. Overlapping tiles blend with a
 * Hann window.
 */
object TemporalMerger {

    private const val T = TileGrid.TILE

    /** Marker in the per-pixel statistic: the reference is clipped here, so no comparison was possible. */
    private const val REF_CLIPPED = -1f

    private val window: FloatArray = FloatArray(T) { (0.5 - 0.5 * cos(2 * PI * (it + 0.5) / T)).toFloat() }

    fun merge(
        ref: Frame,
        alts: List<Frame>,
        fields: List<MotionField>,
        noise: NoiseModel,
        params: MergeParams = MergeParams(),
        threads: Int = 1
    ): MergeResult {
        require(alts.size == fields.size) { "one motion field per alternate frame" }
        val w = ref.plane.width
        val h = ref.plane.height
        alts.forEach { require(it.plane.width == w && it.plane.height == h) { "all frames must share one size" } }
        fields.forEach {
            require(it.tilesX == TileGrid.tilesFor(w) && it.tilesY == TileGrid.tilesFor(h)) { "motion field does not match the frame size" }
        }

        val num = FloatArray(w * h)
        val den = FloatArray(w * h)
        val refCommon = commonValues(ref)
        val tilesX = TileGrid.tilesFor(w)
        val tilesY = TileGrid.tilesFor(h)

        // Tile rows ky and ky+2 do not overlap, so all even rows run in parallel, then all odd rows. The schedule (and
        // therefore the result) does not depend on the thread count.
        val workers = maxOf(1, threads)
        val scratches = List(workers) { TileScratch() }
        // One accumulator per tile row, reduced in row order, so the statistics do not depend on thread scheduling.
        val accumulators = List(tilesY) { MergeAccumulators(alts.size) }

        val processRow = { ky: Int, scratch: TileScratch, acc: MergeAccumulators ->
            val sumWeight = acc.sumWeight
            val sumWindow = acc.sumWindow
            val sumResidual = acc.sumResidual
            val residualCount = acc.residualCount
            val tilesRejected = acc.tilesRejected
            val tilesConsidered = acc.tilesConsidered
            val r = scratch.r
            val grad = scratch.grad
            val a = scratch.a
            val d = scratch.d
            val tStat = scratch.tStat
            val rob = scratch.rob
            val ivAlt = scratch.ivAlt
            val colTaps = scratch.colTaps
            val rowTaps = scratch.rowTaps

            for (kx in 0 until tilesX) {
                val x0 = TileGrid.origin(kx)
                val y0 = TileGrid.origin(ky)

                // Reference tile.
                for (v in 0 until T) for (u in 0 until T) {
                    val x = x0 + u
                    val y = y0 + v
                    val i = v * T + u
                    if (x < 0 || y < 0 || x >= w || y >= h) {
                        r[i] = Float.NaN
                        grad[i] = 0f
                        continue
                    }
                    val value = refCommon[y * w + x]
                    r[i] = value
                    grad[i] = gradientMagnitude(refCommon, w, h, x, y)
                    if (!value.isNaN()) {
                        val iv = (1.0 / noise.variance(max(value, 0f).toDouble())).toFloat()
                        val win = window[u] * window[v]
                        num[y * w + x] += win * iv * value
                        den[y * w + x] += win * iv
                    }
                }

                for ((ai, alt) in alts.withIndex()) {
                    val field = fields[ai]
                    val fi = field.index(kx, ky)
                    val dxv = field.dx[fi]
                    val dyv = field.dy[fi]
                    val ix0 = floor(dxv).toInt()
                    val iy0 = floor(dyv).toInt()
                    catmullRom(dxv - ix0, colTaps)
                    catmullRom(dyv - iy0, rowTaps)
                    val rad = alt.radiometry
                    val sat = rad.saturationDn
                    val scale = 1.0 / (rad.range * rad.gain)
                    val g = rad.gain
                    val plane = alt.plane

                    // Aligned alternate samples (NaN where out of bounds or any tap is clipped).
                    for (v in 0 until T) for (u in 0 until T) {
                        val i = v * T + u
                        val bx = x0 + u + ix0
                        val by = y0 + v + iy0
                        if (bx - 1 < 0 || by - 1 < 0 || bx + 2 >= w || by + 2 >= h) {
                            a[i] = Float.NaN
                            continue
                        }
                        var acc = 0.0
                        var valid = true
                        for (ty in 0 until 4) {
                            var rowAcc = 0.0
                            val rowBase = (by - 1 + ty) * w + (bx - 1)
                            for (tx in 0 until 4) {
                                val dn = plane.data[rowBase + tx].toInt() and 0xFFFF
                                if (dn >= sat) {
                                    valid = false
                                    break
                                }
                                rowAcc += colTaps[tx] * ((dn - rad.blackLevel) * scale)
                            }
                            if (!valid) break
                            acc += rowTaps[ty] * rowAcc
                        }
                        a[i] = if (valid) acc.toFloat() else Float.NaN
                    }

                    // Differences against the reference. Where the reference is clipped but this sample is valid there is nothing
                    // to compare against: the sample is kept (it is the only information) subject to the tile-level check.
                    for (i in 0 until T * T) d[i] = if (a[i].isNaN() || r[i].isNaN()) Float.NaN else a[i] - r[i]

                    var tSum = 0.0
                    var tCount = 0
                    for (v in 0 until T) for (u in 0 until T) {
                        val i = v * T + u
                        if (a[i].isNaN()) {
                            tStat[i] = Float.NaN
                            continue
                        }
                        val refValid = !r[i].isNaN()
                        val xbar = (if (refValid) max(0.5f * (r[i] + a[i]), 0f) else max(a[i], 0f)).toDouble()
                        val varAlt = noise.variance(g * xbar) / (g * g)
                        ivAlt[i] = (1.0 / varAlt).toFloat()
                        if (!refValid) {
                            tStat[i] = REF_CLIPPED
                            continue
                        }
                        // 3x3 mean of the difference over comparable neighbours inside the tile.
                        var s = 0.0
                        var n = 0
                        for (dv in -1..1) for (du in -1..1) {
                            val nu = u + du
                            val nv = v + dv
                            if (nu < 0 || nv < 0 || nu >= T || nv >= T) continue
                            val dd = d[nv * T + nu]
                            if (!dd.isNaN()) {
                                s += dd
                                n++
                            }
                        }
                        val varRef = noise.variance(xbar)
                        val tol = params.alignTolerancePx * grad[i]
                        val varDs = (varRef + varAlt) / n + tol * tol
                        val mean = s / n
                        val t = (mean * mean / varDs).toFloat()
                        tStat[i] = t
                        tSum += t
                        tCount++
                    }
                    var anyUsable = tCount > 0
                    if (!anyUsable) for (i in 0 until T * T) if (tStat[i] == REF_CLIPPED) { anyUsable = true; break }
                    if (!anyUsable) continue
                    tilesConsidered[ai]++
                    if (tCount > 0 && tSum / tCount > params.tileRejectMeanT) {
                        tilesRejected[ai]++
                        // A rejected tile still counts toward the denominator of the frame's mean weight, with weight 0.
                        for (v in 0 until T) for (u in 0 until T) {
                            val x = x0 + u
                            val y = y0 + v
                            if (x >= 0 && y >= 0 && x < w && y < h && !a[v * T + u].isNaN()) sumWindow[ai] += (window[u] * window[v]).toDouble()
                        }
                        continue
                    }
                    for (i in 0 until T * T) {
                        val t = tStat[i]
                        rob[i] = when {
                            t.isNaN() -> 0f
                            t == REF_CLIPPED -> 1f
                            t >= params.rejectT -> 0f
                            else -> {
                                val q = 1f - t / params.rejectT.toFloat()
                                q * q
                            }
                        }
                    }
                    for (v in 0 until T) for (u in 0 until T) {
                        val i = v * T + u
                        val x = x0 + u
                        val y = y0 + v
                        if (x < 0 || y < 0 || x >= w || y >= h || a[i].isNaN()) continue
                        val win = window[u] * window[v]
                        val wgt = win * rob[i] * ivAlt[i]
                        num[y * w + x] += wgt * a[i]
                        den[y * w + x] += wgt
                        sumWeight[ai] += (win * rob[i]).toDouble()
                        sumWindow[ai] += win.toDouble()
                        if (!d[i].isNaN()) {
                            sumResidual[ai] += abs(d[i]).toDouble()
                            residualCount[ai]++
                        }
                    }
                }
            }
        }

        for (phase in 0..1) {
            val rows = (phase until tilesY step 2).toList()
            Parallel.run(rows.size, workers) { index, worker -> processRow(rows[index], scratches[worker], accumulators[rows[index]]) }
        }
        val sumWeight = DoubleArray(alts.size) { ai -> accumulators.sumOf { it.sumWeight[ai] } }
        val sumWindow = DoubleArray(alts.size) { ai -> accumulators.sumOf { it.sumWindow[ai] } }
        val sumResidual = DoubleArray(alts.size) { ai -> accumulators.sumOf { it.sumResidual[ai] } }
        val residualCount = LongArray(alts.size) { ai -> accumulators.sumOf { it.residualCount[ai] } }
        val tilesRejected = IntArray(alts.size) { ai -> accumulators.sumOf { it.tilesRejected[ai] } }
        val tilesConsidered = IntArray(alts.size) { ai -> accumulators.sumOf { it.tilesConsidered[ai] } }

        val out = FloatPlane(w, h)
        for (i in out.data.indices) {
            out.data[i] = if (den[i] > 0f) num[i] / den[i] else {
                val fallback = refCommon[i]
                if (fallback.isNaN()) 1f else fallback // clipped everywhere: report the clipping level
            }
        }
        val stats = alts.indices.map { ai ->
            FrameMergeStats(
                altIndex = ai,
                meanWeight = if (sumWindow[ai] > 0) sumWeight[ai] / sumWindow[ai] else 0.0,
                tilesRejected = tilesRejected[ai],
                tilesTotal = tilesConsidered[ai],
                meanAbsResidual = if (residualCount[ai] > 0) sumResidual[ai] / residualCount[ai] else Double.NaN
            )
        }
        return MergeResult(out, stats)
    }

    /** Reference values on the common scale; NaN where clipped. */
    private fun commonValues(frame: Frame): FloatArray {
        val p = frame.plane
        val r = frame.radiometry
        val sat = r.saturationDn
        return FloatArray(p.data.size) {
            val dn = p.data[it].toInt() and 0xFFFF
            if (dn >= sat) Float.NaN else r.toCommon(dn)
        }
    }

    private fun gradientMagnitude(values: FloatArray, w: Int, h: Int, x: Int, y: Int): Float {
        val xm = values[y * w + maxOf(x - 1, 0)]
        val xp = values[y * w + minOf(x + 1, w - 1)]
        val ym = values[maxOf(y - 1, 0) * w + x]
        val yp = values[minOf(y + 1, h - 1) * w + x]
        if (xm.isNaN() || xp.isNaN() || ym.isNaN() || yp.isNaN()) return 0f
        val gx = (xp - xm) * 0.5f
        val gy = (yp - ym) * 0.5f
        return sqrt(gx * gx + gy * gy)
    }

    /** Catmull-Rom taps for offsets -1, 0, +1, +2 at fractional position f in [0, 1). */
    private fun catmullRom(f: Float, out: FloatArray) {
        val f2 = f * f
        val f3 = f2 * f
        out[0] = -0.5f * f3 + f2 - 0.5f * f
        out[1] = 1.5f * f3 - 2.5f * f2 + 1f
        out[2] = -1.5f * f3 + 2f * f2 + 0.5f * f
        out[3] = 0.5f * f3 - 0.5f * f2
    }
}

/** Aligns every alternate frame to the reference and merges. */
object TemporalMerge {
    /** Default worker count: all cores up to 8 (alignment of the alternate frames and the merge tile rows are parallel). */
    fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    fun run(
        frames: List<Frame>,
        referenceIndex: Int,
        noise: NoiseModel,
        mergeParams: MergeParams = MergeParams(),
        alignParams: AlignParams = AlignParams(),
        threads: Int = defaultThreads(),
        /** Per frame index (the reference's entry is ignored), in frame pixels; null entries align unseeded. */
        seeds: List<AlignmentSeed?>? = null
    ): MergeResult {
        require(seeds == null || seeds.size == frames.size) { "one seed slot per frame" }
        require(frames.isNotEmpty()) { "no frames" }
        require(referenceIndex in frames.indices) { "reference index out of range" }
        val ref = frames[referenceIndex]
        val alts = frames.filterIndexed { i, _ -> i != referenceIndex }
        val altSeeds = seeds?.filterIndexed { i, _ -> i != referenceIndex }
        val refProxy = AlignmentProxy.of(ref)
        val aligner = TileAligner(alignParams)
        val fields = arrayOfNulls<MotionField>(alts.size)
        Parallel.run(alts.size, threads) { index, _ -> fields[index] = aligner.align(refProxy, AlignmentProxy.of(alts[index]), altSeeds?.get(index)) }
        return TemporalMerger.merge(ref, alts, fields.map { it!! }, noise, mergeParams, threads)
    }
}

/** Minimal work-sharing loop: [count] items spread over up to [workers] threads; `worker` identifies the thread. */
internal object Parallel {
    fun run(count: Int, workers: Int, body: (index: Int, worker: Int) -> Unit) {
        val n = minOf(maxOf(1, workers), count)
        if (n <= 1) {
            for (i in 0 until count) body(i, 0)
            return
        }
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val threads = List(n) { worker ->
            Thread {
                try {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= count) break
                        body(i, worker)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        failure.get()?.let { throw it }
    }
}
