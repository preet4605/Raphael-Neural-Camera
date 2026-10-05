package com.neuralcamera.isp.dng

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes small DNG-shaped TIFF files for tests. Layout: IFD0 (thumbnail-like) -> SubIFD (raw) -> EXIF IFD -> values -> raw data. */
internal object TestDng {

    class Entry(val tag: Int, val type: Int, val count: Int, val bytes: ByteArray)

    class Spec(
        val width: Int,
        val height: Int,
        val mosaic: ShortArray,
        val order: ByteOrder = ByteOrder.LITTLE_ENDIAN,
        val cfaColors: IntArray? = intArrayOf(0, 1, 1, 2),
        val blacks: DoubleArray? = doubleArrayOf(64.0, 64.0, 64.0, 64.0),
        val blackRepeat: IntArray? = intArrayOf(2, 2),
        val white: Long? = 4095,
        val noiseProfile: DoubleArray? = null,
        val neutral: DoubleArray? = null,
        val activeArea: LongArray? = null,
        val exposureSeconds: Double? = null,
        val iso: Int? = null,
        val bits: Int = 16,
        val compression: Int = 1,
        val photometric: Long? = 32803
    )

    private fun bb(order: ByteOrder, size: Int) = ByteBuffer.allocate(size).order(order)

    private fun shorts(order: ByteOrder, vararg v: Int) = bb(order, v.size * 2).also { b -> v.forEach { b.putShort(it.toShort()) } }.array()
    private fun longs(order: ByteOrder, vararg v: Long) = bb(order, v.size * 4).also { b -> v.forEach { b.putInt(it.toInt()) } }.array()
    private fun doubles(order: ByteOrder, v: DoubleArray) = bb(order, v.size * 8).also { b -> v.forEach { b.putDouble(it) } }.array()
    private fun rationals(order: ByteOrder, v: DoubleArray) = bb(order, v.size * 8).also { b ->
        v.forEach { x -> b.putInt((x * 1_000_000).toLong().toInt()); b.putInt(1_000_000) }
    }.array()

    fun write(file: File, spec: Spec) {
        val o = spec.order
        val raw = bb(o, spec.mosaic.size * 2).also { b -> spec.mosaic.forEach { b.putShort(it) } }.array()

        fun build(rawOffset: Long, subIfdOffset: Long, exifOffset: Long): List<List<Entry>> {
            val ifd0 = mutableListOf(
                Entry(254, 4, 1, longs(o, 1)),                 // NewSubfileType: reduced-resolution image
                Entry(256, 4, 1, longs(o, 1)), Entry(257, 4, 1, longs(o, 1)),
                Entry(330, 4, 1, longs(o, subIfdOffset)),
                Entry(34665, 4, 1, longs(o, exifOffset))
            )
            val raws = mutableListOf(
                Entry(254, 4, 1, longs(o, 0)),
                Entry(256, 4, 1, longs(o, spec.width.toLong())), Entry(257, 4, 1, longs(o, spec.height.toLong())),
                Entry(258, 3, 1, shorts(o, spec.bits)), Entry(259, 3, 1, shorts(o, spec.compression)),
                Entry(273, 4, 1, longs(o, rawOffset)), Entry(277, 3, 1, shorts(o, 1)),
                Entry(279, 4, 1, longs(o, raw.size.toLong()))
            )
            spec.photometric?.let { raws.add(Entry(262, 3, 1, shorts(o, it.toInt()))) }
            spec.cfaColors?.let {
                raws.add(Entry(33421, 3, 2, shorts(o, 2, 2)))
                raws.add(Entry(33422, 1, 4, ByteArray(4) { i -> it[i].toByte() }))
            }
            spec.blacks?.let {
                spec.blackRepeat?.let { r -> raws.add(Entry(50713, 3, 2, shorts(o, r[0], r[1]))) }
                raws.add(Entry(50714, 5, it.size, rationals(o, it))) // RATIONAL, as some writers use
            }
            spec.white?.let { raws.add(Entry(50717, 4, 1, longs(o, it))) }
            spec.noiseProfile?.let { raws.add(Entry(51041, 12, it.size, doubles(o, it))) }
            spec.neutral?.let { ifd0.add(Entry(50728, 5, 3, rationals(o, it))) }
            spec.activeArea?.let { raws.add(Entry(50829, 4, 4, longs(o, *it))) }
            val exif = mutableListOf<Entry>()
            spec.exposureSeconds?.let { exif.add(Entry(33434, 5, 1, rationals(o, doubleArrayOf(it)))) }
            spec.iso?.let { exif.add(Entry(34855, 3, 1, shorts(o, it))) }
            return listOf(ifd0, raws, exif)
        }

        fun layout(ifds: List<List<Entry>>): Triple<List<Long>, Long, Long> {
            var pos = 8L
            val offsets = ifds.map { e -> pos.also { pos += 2 + 12L * e.size + 4 } }
            val valuesStart = pos
            val valueBytes = ifds.sumOf { ifd -> ifd.sumOf { if (it.bytes.size > 4) it.bytes.size + (it.bytes.size % 2) else 0 }.toLong() }
            return Triple(offsets, valuesStart, valuesStart + valueBytes)
        }

        // Two passes: sizes do not depend on the offset values themselves.
        val provisional = layout(build(0, 0, 0))
        val rawOffset = provisional.third
        val finalIfds = build(rawOffset, provisional.first[1], provisional.first[2])
        val (offsets, valuesStart, _) = layout(finalIfds)

        val out = ByteArrayOutputStream()
        out.write(if (o == ByteOrder.LITTLE_ENDIAN) byteArrayOf('I'.code.toByte(), 'I'.code.toByte()) else byteArrayOf('M'.code.toByte(), 'M'.code.toByte()))
        out.write(shorts(o, 42))
        out.write(longs(o, offsets[0]))
        var valuePos = valuesStart
        val valuesOut = ByteArrayOutputStream()
        for ((index, ifd) in finalIfds.withIndex()) {
            val sorted = ifd.sortedBy { it.tag }
            val body = bb(o, 2 + 12 * sorted.size + 4)
            body.putShort(sorted.size.toShort())
            for (e in sorted) {
                body.putShort(e.tag.toShort()); body.putShort(e.type.toShort()); body.putInt(e.count)
                if (e.bytes.size <= 4) {
                    body.put(e.bytes); repeat(4 - e.bytes.size) { body.put(0) }
                } else {
                    body.putInt(valuePos.toInt())
                    valuesOut.write(e.bytes)
                    if (e.bytes.size % 2 == 1) valuesOut.write(0)
                    valuePos += e.bytes.size + (e.bytes.size % 2)
                }
            }
            body.putInt(0) // no next IFD
            out.write(body.array())
        }
        out.write(valuesOut.toByteArray())
        out.write(raw)
        file.writeBytes(out.toByteArray())
    }
}
