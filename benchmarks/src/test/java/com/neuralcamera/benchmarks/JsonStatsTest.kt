package com.neuralcamera.benchmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonStatsTest {

    @Test
    fun jsonRoundTripsNestedValues() {
        val doc = linkedMapOf<String, Any?>(
            "name" to "a \"quoted\"\nline\\",
            "count" to 3,
            "big" to 9_007_199_254_740_993L,
            "ratio" to 0.25,
            "ok" to true,
            "none" to null,
            "list" to listOf(1, 2.5, "x", listOf<Any?>(), emptyMap<String, Any?>()),
            "arrays" to mapOf("ints" to intArrayOf(1, 2), "floats" to floatArrayOf(0.5f), "doubles" to doubleArrayOf(1e-5))
        )

        for (pretty in listOf(true, false)) {
            val parsed = Json.parse(Json.stringify(doc, pretty)) as Map<*, *>

            assertEquals("a \"quoted\"\nline\\", parsed["name"])
            assertEquals(3L, parsed["count"])
            assertEquals(9_007_199_254_740_993L, parsed["big"])
            assertEquals(0.25, parsed["ratio"] as Double, 0.0)
            assertEquals(true, parsed["ok"])
            assertNull(parsed["none"])
            assertEquals(listOf(1L, 2.5, "x", emptyList<Any?>(), emptyMap<String, Any?>()), parsed["list"])
            assertEquals(listOf(1L, 2L), (parsed["arrays"] as Map<*, *>)["ints"])
            assertEquals(listOf(1.0E-5), (parsed["arrays"] as Map<*, *>)["doubles"])
        }
    }

    @Test
    fun nonFiniteNumbersBecomeNullSoOutputStaysValidJson() {
        val text = Json.stringify(mapOf("a" to Double.NaN, "b" to Float.POSITIVE_INFINITY), pretty = false)
        assertEquals("""{"a":null,"b":null}""", text)
    }

    @Test
    fun parserHandlesEscapesAndRejectsGarbage() {
        assertEquals("é/\t", Json.parse("\"\\u00e9\\/\\t\""))
        assertEquals(-12.5e2, Json.parse("-12.5e2"))
        assertThrows(IllegalArgumentException::class.java) { Json.parse("{\"a\":1,}") }
        assertThrows(IllegalArgumentException::class.java) { Json.parse("[1 2]") }
        assertThrows(IllegalArgumentException::class.java) { Json.parse("{\"a\":1} x") }
        assertThrows(IllegalArgumentException::class.java) { Json.parse("\"unterminated") }
    }

    @Test
    fun percentilesUseNearestRankAndMedianIsStandard() {
        val values = DoubleArray(300) { (it + 1).toDouble() } // 1..300
        val s = Stats.summarize(values.reversedArray())

        assertEquals(300, s.count)
        assertEquals(1.0, s.min, 0.0)
        assertEquals(300.0, s.max, 0.0)
        assertEquals(150.5, s.median, 1e-12)
        assertEquals(285.0, s.p95, 0.0)   // ceil(0.95*300) = 285
        assertEquals(297.0, s.p99, 0.0)   // ceil(0.99*300) = 297
        assertEquals(150.5, s.mean, 1e-12)
        assertEquals(7.0, Stats.percentileNearestRank(doubleArrayOf(7.0), 95.0), 0.0)
        assertTrue(Stats.percentileNearestRank(doubleArrayOf(1.0, 2.0), 100.0) == 2.0)
    }
}
