package com.neuralcamera.isp.color

/** Row-major 3x3 matrix; just enough linear algebra for colour transforms. */
class Matrix3(val m: DoubleArray) {
    init {
        require(m.size == 9) { "a 3x3 matrix needs nine values" }
    }

    operator fun times(o: Matrix3) = Matrix3(DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        m[r * 3] * o.m[c] + m[r * 3 + 1] * o.m[3 + c] + m[r * 3 + 2] * o.m[6 + c]
    })

    fun apply(v: DoubleArray) = DoubleArray(3) { r -> m[r * 3] * v[0] + m[r * 3 + 1] * v[1] + m[r * 3 + 2] * v[2] }

    fun inverse(): Matrix3 {
        val a = m
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        require(kotlin.math.abs(det) > 1e-12) { "matrix is singular" }
        val inv = doubleArrayOf(
            c00, a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            c01, a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            c02, a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3]
        )
        return Matrix3(DoubleArray(9) { inv[it] / det })
    }

    companion object {
        val IDENTITY = Matrix3(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        fun diag(a: Double, b: Double, c: Double) = Matrix3(doubleArrayOf(a, 0.0, 0.0, 0.0, b, 0.0, 0.0, 0.0, c))
    }
}
