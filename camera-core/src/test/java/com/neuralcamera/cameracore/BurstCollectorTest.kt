package com.neuralcamera.cameracore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class BurstCollectorTest {

    private class Img(val id: Int) : AutoCloseable {
        var closed = 0
        override fun close() { closed++ }
    }

    @Test
    fun pairsByTimestampNotArrivalOrderAndSortsByTime() {
        val c = BurstCollector<Img, String>(3)
        val imgs = listOf(Img(0), Img(1), Img(2))
        // Images in order 300, 100, 200; results arrive in a different order, some before their image.
        c.onResult(200, "r200")
        c.onImage(300, imgs[2]); c.onImage(100, imgs[0]); c.onImage(200, imgs[1])
        c.onResult(300, "r300"); c.onResult(100, "r100")
        val out = c.await(100)
        assertTrue(out.complete)
        assertFalse(out.timedOut)
        assertEquals(listOf(100L, 200L, 300L), out.pairs.map { it.timestampNs })
        assertEquals(listOf("r100", "r200", "r300"), out.pairs.map { it.result })
        assertEquals(listOf(0, 1, 2), out.pairs.map { it.image.id })
        assertTrue(imgs.all { it.closed == 0 }) // ownership passes to the caller
    }

    @Test
    fun lostFrameEndsTheWaitEarlyAndReportsIncomplete() {
        val c = BurstCollector<Img, String>(3)
        c.onImage(1, Img(1)); c.onResult(1, "a")
        c.onImage(2, Img(2)); c.onResult(2, "b")
        c.onFrameLost()
        val start = System.nanoTime()
        val out = c.await(5_000)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_000)
        assertFalse(out.complete)
        assertFalse(out.timedOut)
        assertEquals(1, out.failedOrLost)
        assertEquals(2, out.pairs.size)
    }

    @Test
    fun timeoutClosesUnpairedImagesAndRejectsLateArrivals() {
        val c = BurstCollector<Img, String>(2)
        val orphan = Img(9)
        c.onImage(1, Img(1)); c.onResult(1, "a")
        c.onImage(5, orphan) // its result never comes
        c.onResult(7, "stray")
        val out = c.await(30)
        assertTrue(out.timedOut)
        assertEquals(1, out.pairs.size)
        assertEquals(1, out.imagesWithoutResult)
        assertEquals(1, out.resultsWithoutImage)
        assertEquals(1, orphan.closed)
        val late = Img(10)
        c.onImage(8, late)
        assertEquals(1, late.closed)
    }

    @Test
    fun duplicateTimestampClosesTheReplacedImage() {
        val c = BurstCollector<Img, String>(1)
        val first = Img(1); val second = Img(2)
        c.onImage(4, first); c.onImage(4, second); c.onResult(4, "r")
        val out = c.await(50)
        assertEquals(1, first.closed)
        assertEquals(2, out.pairs.single().image.id)
    }

    @Test
    fun worksAcrossThreads() {
        val c = BurstCollector<Img, Int>(20)
        val t1 = thread { for (i in 0 until 20) c.onImage(i.toLong(), Img(i)) }
        val t2 = thread { for (i in 19 downTo 0) c.onResult(i.toLong(), i) }
        val out = c.await(2_000)
        t1.join(); t2.join()
        assertTrue(out.complete)
        assertTrue(out.pairs.all { it.image.id == it.result })
    }
}
