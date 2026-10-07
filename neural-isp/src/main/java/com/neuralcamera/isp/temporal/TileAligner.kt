package com.neuralcamera.isp.temporal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Per-tile translation of an alternate frame relative to the reference, on the overlapping tile grid used by the merger:
 * tiles are [TileGrid.TILE] pixels square at stride [TileGrid.STRIDE], tile k starting at `-STRIDE + k * STRIDE`.
 * (dx, dy) means: reference pixel (x, y) corresponds to alternate pixel (x + dx, y + dy).
 */
class MotionField(
    val tilesX: Int,
    val tilesY: Int,
    val dx: FloatArray,
    val dy: FloatArray,
    /** Mean absolute difference after alignment (normalized units); +Inf where the tile could not be matched. */
    val cost: FloatArray
) {
    fun index(kx: Int, ky: Int) = ky * tilesX + kx
}

object TileGrid {
    const val TILE = 16
    const val STRIDE = 8

    fun tilesFor(size: Int) = ceil(size / STRIDE.toDouble()).toInt() + 1
    fun origin(k: Int) = -STRIDE + k * STRIDE
}

/** Builds the alignment proxy: common-scale values, NaN where the frame is clipped (no usable information). */
object AlignmentProxy {
    fun of(frame: Frame): FloatPlane {
        val p = frame.plane
        val r = frame.radiometry
        val sat = r.saturationDn
        val out = FloatPlane(p.width, p.height)
        for (i in out.data.indices) {
            val dn = p.data[i].toInt() and 0xFFFF
            out.data[i] = if (dn >= sat) Float.NaN else r.toCommon(dn)
        }
        return out
    }

    /** 2x2 mean over valid samples; NaN when none is valid. Odd trailing rows/columns are dropped. */
    fun downsample(src: FloatPlane): FloatPlane {
        val w = max(1, src.width / 2)
        val h = max(1, src.height / 2)
        val out = FloatPlane(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var n = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val sx = min(src.width - 1, 2 * x + dx)
                    val sy = min(src.height - 1, 2 * y + dy)
                    val v = src.data[sy * src.width + sx]
                    if (!v.isNaN()) {
                        sum += v
                        n++
                    }
                }
                out.data[y * w + x] = if (n == 0) Float.NaN else sum / n
            }
        }
        return out
    }

    fun pyramid(base: FloatPlane, levels: Int): List<FloatPlane> {
        val list = mutableListOf(base)
        repeat(levels - 1) { list.add(downsample(list.last())) }
        return list
    }

    /** Levels so that the coarsest plane stays at least ~64 px on its short side (1..4). */
    fun levelsFor(width: Int, height: Int): Int {
        var levels = 1
        var s = min(width, height)
        while (s / 2 >= 64 && levels < 4) {
            s /= 2
            levels++
        }
        return levels
    }
}

/**
 * Global translation hint for an alternate frame, in pixels of the plane passed to [TileAligner.align] and with the
 * [MotionField] sign convention (e.g. from the gyro). It only adds candidates: the zero-motion search still runs and the
 * lower matching cost wins per tile, so a wrong seed is taken only where it matches better (possible on repetitive
 * texture). A missing seed changes nothing.
 */
data class AlignmentSeed(val dx: Float, val dy: Float) {
    init {
        require(dx.isFinite() && dy.isFinite()) { "seed must be finite" }
    }

    fun scaled(factor: Float) = AlignmentSeed(dx * factor, dy * factor)
}

data class AlignParams(
    /** Integer search radius at the coarsest pyramid level (in that level's pixels). */
    val coarseRadius: Int = 4,
    /** Minimum fraction of a tile's pixels that must be comparable (in bounds, unclipped) to trust a match. */
    val minValidFraction: Float = 0.5f,
    val lkIterations: Int = 4,
    /** Minimum gradient energy (smallest structure-tensor eigenvalue, per pixel) for sub-pixel refinement. */
    val minTextureEigenPerPixel: Double = 2.5e-4
)

/**
 * Coarse-to-fine tile matching with Lucas-Kanade sub-pixel refinement at full resolution. Translation only per tile, so
 * rotation and large parallax appear as spatially varying vectors and, where unresolved, as high residual that the merger
 * rejects.
 */
class TileAligner(private val params: AlignParams = AlignParams()) {

    fun align(ref: FloatPlane, alt: FloatPlane, seed: AlignmentSeed? = null): MotionField {
        require(ref.width == alt.width && ref.height == alt.height) { "frames must have the same size" }
        val levels = AlignmentProxy.levelsFor(ref.width, ref.height)
        val refPyr = AlignmentProxy.pyramid(ref, levels)
        val altPyr = AlignmentProxy.pyramid(alt, levels)

        var field: MotionField? = null
        for (level in levels - 1 downTo 0) {
            val levelSeed = seed?.scaled(1f / (1 shl level))?.let { Math.round(it.dx) to Math.round(it.dy) }
            field = matchLevel(refPyr[level], altPyr[level], field, levelSeed)
        }
        val finest = field!!
        refineSubPixel(refPyr[0], altPyr[0], finest)
        fillUnreliable(finest)
        return finest
    }

    private fun matchLevel(ref: FloatPlane, alt: FloatPlane, coarse: MotionField?, seed: Pair<Int, Int>?): MotionField {
        val tx = TileGrid.tilesFor(ref.width)
        val ty = TileGrid.tilesFor(ref.height)
        val dx = FloatArray(tx * ty)
        val dy = FloatArray(tx * ty)
        val cost = FloatArray(tx * ty) { Float.POSITIVE_INFINITY }
        for (ky in 0 until ty) {
            for (kx in 0 until tx) {
                val x0 = TileGrid.origin(kx)
                val y0 = TileGrid.origin(ky)
                var bestX = 0
                var bestY = 0
                var best = Float.POSITIVE_INFINITY
                if (coarse == null) {
                    val r = params.coarseRadius
                    // Full search around zero, and around the seed when it lies outside that window.
                    val centres = listOfNotNull(0 to 0, seed?.takeIf { (sx, sy) -> abs(sx) > r || abs(sy) > r })
                    for ((cx, cy) in centres) for (vy in cy - r..cy + r) for (vx in cx - r..cx + r) {
                        val c = tileCost(ref, alt, x0, y0, vx, vy)
                        if (c < best || (c == best && abs(vx) + abs(vy) < abs(bestX) + abs(bestY))) {
                            best = c; bestX = vx; bestY = vy
                        }
                    }
                } else {
                    // Candidates: the parent tiles' vectors scaled up, zero, and the seed.
                    val cands = LinkedHashSet<Long>()
                    cands.add(pack(0, 0))
                    if (seed != null) cands.add(pack(seed.first, seed.second))
                    for (py in intArrayOf(ky / 2, (ky + 1) / 2)) for (px in intArrayOf(kx / 2, (kx + 1) / 2)) {
                        val ci = coarse.index(px.coerceIn(0, coarse.tilesX - 1), py.coerceIn(0, coarse.tilesY - 1))
                        if (coarse.cost[ci].isFinite()) cands.add(pack(Math.round(coarse.dx[ci] * 2), Math.round(coarse.dy[ci] * 2)))
                    }
                    for (c in cands) {
                        val vx = unpackX(c)
                        val vy = unpackY(c)
                        val cc = tileCost(ref, alt, x0, y0, vx, vy)
                        if (cc < best) { best = cc; bestX = vx; bestY = vy }
                    }
                    // Local descent around the best candidate.
                    for (step in 0 until 4) {
                        var improved = false
                        val cx = bestX
                        val cy = bestY
                        for (oy in -1..1) for (ox in -1..1) {
                            if (ox == 0 && oy == 0) continue
                            val cc = tileCost(ref, alt, x0, y0, cx + ox, cy + oy)
                            if (cc < best) { best = cc; bestX = cx + ox; bestY = cy + oy; improved = true }
                        }
                        if (!improved) break
                    }
                }
                val i = ky * tx + kx
                dx[i] = bestX.toFloat()
                dy[i] = bestY.toFloat()
                cost[i] = best
            }
        }
        return MotionField(tx, ty, dx, dy, cost)
    }

    private fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    private fun unpackX(v: Long): Int = (v shr 32).toInt()
    private fun unpackY(v: Long): Int = v.toInt()

    /** Mean absolute difference over comparable pixels; +Inf if too few are comparable. */
    private fun tileCost(ref: FloatPlane, alt: FloatPlane, x0: Int, y0: Int, vx: Int, vy: Int): Float {
        var sum = 0f
        var n = 0
        var inRef = 0
        for (v in 0 until TileGrid.TILE) {
            val yr = y0 + v
            if (yr < 0 || yr >= ref.height) continue
            val ya = yr + vy
            for (u in 0 until TileGrid.TILE) {
                val xr = x0 + u
                if (xr < 0 || xr >= ref.width) continue
                inRef++
                val xa = xr + vx
                if (xa < 0 || xa >= alt.width || ya < 0 || ya >= alt.height) continue
                val r = ref.data[yr * ref.width + xr]
                val a = alt.data[ya * alt.width + xa]
                if (r.isNaN() || a.isNaN()) continue
                sum += abs(r - a)
                n++
            }
        }
        if (inRef < TileGrid.TILE * TileGrid.TILE / 4 || n < params.minValidFraction * inRef) return Float.POSITIVE_INFINITY
        return sum / n
    }

    private fun bilinear(p: FloatPlane, x: Float, y: Float): Float {
        if (x < 0f || y < 0f || x > p.width - 1 || y > p.height - 1) return Float.NaN
        val x0 = floor(x).toInt().coerceAtMost(p.width - 2).coerceAtLeast(0)
        val y0 = floor(y).toInt().coerceAtMost(p.height - 2).coerceAtLeast(0)
        val x1 = min(x0 + 1, p.width - 1)
        val y1 = min(y0 + 1, p.height - 1)
        val fx = x - x0
        val fy = y - y0
        val a = p.data[y0 * p.width + x0]
        val b = p.data[y0 * p.width + x1]
        val c = p.data[y1 * p.width + x0]
        val d = p.data[y1 * p.width + x1]
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** Gauss-Newton Lucas-Kanade per tile starting from the integer vector. */
    private fun refineSubPixel(ref: FloatPlane, alt: FloatPlane, field: MotionField) {
        for (ky in 0 until field.tilesY) {
            for (kx in 0 until field.tilesX) {
                val i = field.index(kx, ky)
                if (!field.cost[i].isFinite()) continue
                val x0 = TileGrid.origin(kx)
                val y0 = TileGrid.origin(ky)
                var vx = field.dx[i]
                var vy = field.dy[i]
                var ok = true
                for (iter in 0 until params.lkIterations) {
                    var hxx = 0.0
                    var hxy = 0.0
                    var hyy = 0.0
                    var bx = 0.0
                    var by = 0.0
                    var n = 0
                    for (v in 0 until TileGrid.TILE) {
                        val yr = y0 + v
                        if (yr < 0 || yr >= ref.height) continue
                        for (u in 0 until TileGrid.TILE) {
                            val xr = x0 + u
                            if (xr < 0 || xr >= ref.width) continue
                            val r = ref.data[yr * ref.width + xr]
                            if (r.isNaN()) continue
                            val xa = xr + vx
                            val ya = yr + vy
                            val a0 = bilinear(alt, xa, ya)
                            val axp = bilinear(alt, xa + 1f, ya)
                            val axm = bilinear(alt, xa - 1f, ya)
                            val ayp = bilinear(alt, xa, ya + 1f)
                            val aym = bilinear(alt, xa, ya - 1f)
                            if (a0.isNaN() || axp.isNaN() || axm.isNaN() || ayp.isNaN() || aym.isNaN()) continue
                            val ix = (axp - axm) * 0.5
                            val iy = (ayp - aym) * 0.5
                            val e = (a0 - r).toDouble()
                            hxx += ix * ix
                            hxy += ix * iy
                            hyy += iy * iy
                            bx += ix * e
                            by += iy * e
                            n++
                        }
                    }
                    if (n < TileGrid.TILE * TileGrid.TILE / 4) { ok = false; break }
                    val disc = sqrt(max(0.0, (hxx - hyy) * (hxx - hyy) / 4 + hxy * hxy))
                    val lambdaMin = (hxx + hyy) / 2 - disc
                    if (lambdaMin < params.minTextureEigenPerPixel * n) { ok = false; break }
                    val det = hxx * hyy - hxy * hxy
                    val ddx = (-(hyy * bx - hxy * by) / det).coerceIn(-1.0, 1.0)
                    val ddy = (-(-hxy * bx + hxx * by) / det).coerceIn(-1.0, 1.0)
                    vx += ddx.toFloat()
                    vy += ddy.toFloat()
                    if (abs(ddx) + abs(ddy) < 0.005) break
                }
                if (ok) {
                    field.dx[i] = vx
                    field.dy[i] = vy
                    var sum = 0f
                    var n = 0
                    for (v in 0 until TileGrid.TILE) for (u in 0 until TileGrid.TILE) {
                        val xr = x0 + u
                        val yr = y0 + v
                        if (xr < 0 || yr < 0 || xr >= ref.width || yr >= ref.height) continue
                        val r = ref.data[yr * ref.width + xr]
                        val a = bilinear(alt, xr + vx, yr + vy)
                        if (r.isNaN() || a.isNaN()) continue
                        sum += abs(r - a)
                        n++
                    }
                    if (n > 0) field.cost[i] = sum / n
                }
            }
        }
    }

    /** Tiles with no usable match take the median vector of reliable 3x3 neighbours (else zero). */
    private fun fillUnreliable(field: MotionField) {
        val snapshotDx = field.dx.copyOf()
        val snapshotDy = field.dy.copyOf()
        val reliable = BooleanArray(field.cost.size) { field.cost[it].isFinite() }
        for (ky in 0 until field.tilesY) {
            for (kx in 0 until field.tilesX) {
                val i = field.index(kx, ky)
                if (reliable[i]) continue
                val xs = ArrayList<Float>()
                val ys = ArrayList<Float>()
                for (oy in -1..1) for (ox in -1..1) {
                    val nx = kx + ox
                    val ny = ky + oy
                    if (nx < 0 || ny < 0 || nx >= field.tilesX || ny >= field.tilesY) continue
                    val ni = field.index(nx, ny)
                    if (reliable[ni]) {
                        xs.add(snapshotDx[ni])
                        ys.add(snapshotDy[ni])
                    }
                }
                if (xs.isNotEmpty()) {
                    xs.sort()
                    ys.sort()
                    field.dx[i] = xs[xs.size / 2]
                    field.dy[i] = ys[ys.size / 2]
                } else {
                    field.dx[i] = 0f
                    field.dy[i] = 0f
                }
            }
        }
    }
}
