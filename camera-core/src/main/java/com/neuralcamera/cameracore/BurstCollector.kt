package com.neuralcamera.cameracore

/**
 * Collects one burst: pairs every arriving image with its capture result by SENSOR_TIMESTAMP (never by arrival order)
 * and lets the capturing thread wait for the whole burst. Images and results may arrive in either order. Frames the
 * camera reports as failed or lost are counted so the wait ends early instead of running to the timeout.
 *
 * Ownership: every image passed in is either returned in [Outcome.pairs] (the caller must close it) or closed here.
 * After [finish] (or a timeout) late arrivals are closed immediately, so nothing leaks into a later burst.
 *
 * Plain Kotlin on purpose: the pairing and lifetime rules are testable on the JVM without a camera.
 */
class BurstCollector<I : AutoCloseable, R>(private val expected: Int) {

    class Pair<I, R>(val timestampNs: Long, val image: I, val result: R)

    class Outcome<I, R>(
        val pairs: List<Pair<I, R>>,
        val expected: Int,
        val failedOrLost: Int,
        /** Images that never received a result (closed by the collector). */
        val imagesWithoutResult: Int,
        /** Results that never received an image (the frame was lost or arrived too late). */
        val resultsWithoutImage: Int,
        val timedOut: Boolean
    ) {
        val complete: Boolean get() = pairs.size == expected
    }

    private val lock = Object()
    private val images = LinkedHashMap<Long, I>()
    private val results = LinkedHashMap<Long, R>()
    private val paired = ArrayList<Pair<I, R>>()
    private var failed = 0
    private var finished = false

    init {
        require(expected > 0) { "a burst needs at least one frame" }
    }

    fun onImage(timestampNs: Long, image: I) {
        synchronized(lock) {
            if (finished) { image.close(); return }
            val prior = images.put(timestampNs, image)
            prior?.close() // duplicate timestamp: keep the newest, never leak the other
            tryPair(timestampNs)
            lock.notifyAll()
        }
    }

    fun onResult(timestampNs: Long, result: R) {
        synchronized(lock) {
            if (finished) return
            results[timestampNs] = result
            tryPair(timestampNs)
            lock.notifyAll()
        }
    }

    /** A capture failed or a buffer was lost; that frame will not arrive. */
    fun onFrameLost() {
        synchronized(lock) {
            if (finished) return
            failed++
            lock.notifyAll()
        }
    }

    private fun tryPair(timestampNs: Long) {
        val image = images[timestampNs] ?: return
        val result = results[timestampNs] ?: return
        images.remove(timestampNs)
        results.remove(timestampNs)
        paired.add(Pair(timestampNs, image, result))
    }

    /** Blocks until every expected frame is paired or accounted for as lost, or [timeoutMs] elapses; then finishes. */
    fun await(timeoutMs: Long): Outcome<I, R> {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (paired.size + failed < expected) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0) break
                lock.wait(remainingMs)
            }
            return finishLocked()
        }
    }

    /** Ends collection now, closing every unpaired image. Safe to call more than once. */
    fun finish(): Outcome<I, R> = synchronized(lock) { finishLocked() }

    private fun finishLocked(): Outcome<I, R> {
        val timedOut = !finished && paired.size + failed < expected
        val unpairedImages = images.size
        val unpairedResults = results.size
        if (!finished) {
            finished = true
            images.values.forEach { runCatching { it.close() } }
            images.clear()
            results.clear()
        }
        return Outcome(paired.sortedBy { it.timestampNs }, expected, failed, unpairedImages, unpairedResults, timedOut)
    }
}
