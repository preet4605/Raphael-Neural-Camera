package com.neuralcamera.isp.dng

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TiffFormatException(message: String) : Exception(message)

/** One IFD entry with its raw value bytes (inline or out of line already resolved). */
class TiffEntry(val tag: Int, val type: Int, val count: Long, val data: ByteArray, val order: ByteOrder) {

    private fun buffer() = ByteBuffer.wrap(data).order(order)

    /** Integer values for BYTE, SHORT, LONG and their signed forms (unsigned widened to Long). */
    fun longs(): LongArray {
        val b = buffer()
        return LongArray(count.toInt()) {
            when (type) {
                TYPE_BYTE, TYPE_UNDEFINED, TYPE_ASCII -> (b.get(it).toLong() and 0xFF)
                TYPE_SBYTE -> b.get(it).toLong()
                TYPE_SHORT -> (b.getShort(it * 2).toLong() and 0xFFFF)
                TYPE_SSHORT -> b.getShort(it * 2).toLong()
                TYPE_LONG, TYPE_IFD -> (b.getInt(it * 4).toLong() and 0xFFFFFFFFL)
                TYPE_SLONG -> b.getInt(it * 4).toLong()
                else -> throw TiffFormatException("tag $tag: type $type has no integer reading")
            }
        }
    }

    /** Numeric values as doubles, including RATIONAL, SRATIONAL, FLOAT and DOUBLE. */
    fun doubles(): DoubleArray {
        val b = buffer()
        return when (type) {
            TYPE_RATIONAL -> DoubleArray(count.toInt()) {
                val n = b.getInt(it * 8).toLong() and 0xFFFFFFFFL
                val d = b.getInt(it * 8 + 4).toLong() and 0xFFFFFFFFL
                if (d == 0L) Double.NaN else n.toDouble() / d
            }
            TYPE_SRATIONAL -> DoubleArray(count.toInt()) {
                val n = b.getInt(it * 8)
                val d = b.getInt(it * 8 + 4)
                if (d == 0) Double.NaN else n.toDouble() / d
            }
            TYPE_FLOAT -> DoubleArray(count.toInt()) { b.getFloat(it * 4).toDouble() }
            TYPE_DOUBLE -> DoubleArray(count.toInt()) { b.getDouble(it * 8) }
            else -> longs().map { it.toDouble() }.toDoubleArray()
        }
    }

    companion object {
        const val TYPE_BYTE = 1
        const val TYPE_ASCII = 2
        const val TYPE_SHORT = 3
        const val TYPE_LONG = 4
        const val TYPE_RATIONAL = 5
        const val TYPE_SBYTE = 6
        const val TYPE_UNDEFINED = 7
        const val TYPE_SSHORT = 8
        const val TYPE_SLONG = 9
        const val TYPE_SRATIONAL = 10
        const val TYPE_FLOAT = 11
        const val TYPE_DOUBLE = 12
        const val TYPE_IFD = 13

        fun typeSize(type: Int): Int = when (type) {
            TYPE_BYTE, TYPE_ASCII, TYPE_SBYTE, TYPE_UNDEFINED -> 1
            TYPE_SHORT, TYPE_SSHORT -> 2
            TYPE_LONG, TYPE_SLONG, TYPE_FLOAT, TYPE_IFD -> 4
            TYPE_RATIONAL, TYPE_SRATIONAL, TYPE_DOUBLE -> 8
            else -> 0
        }
    }
}

class TiffIfd(val offset: Long, val entries: Map<Int, TiffEntry>, val nextOffset: Long) {
    fun entry(tag: Int): TiffEntry? = entries[tag]
    fun longs(tag: Int): LongArray? = entries[tag]?.longs()
    fun doubles(tag: Int): DoubleArray? = entries[tag]?.doubles()
}

/**
 * Minimal TIFF/DNG reader: IFD0, its chain, SubIFDs and the EXIF IFD, plus strip/tile data access. Enough to read the
 * RAW burst DNGs the app writes with DngCreator; it is not a general TIFF library.
 */
class TiffFile private constructor(private val raf: RandomAccessFile, val order: ByteOrder, val ifds: List<TiffIfd>) : AutoCloseable {

    val ifd0: TiffIfd get() = ifds.first()

    /** All IFDs (chain, SubIFDs, EXIF) that carry the given integer tag value. */
    fun findIfd(predicate: (TiffIfd) -> Boolean): TiffIfd? = ifds.firstOrNull(predicate)

    /** Concatenated strip (or tile) data of [ifd] in file order. */
    fun readImageData(ifd: TiffIfd): ByteArray {
        val offsets = ifd.longs(TAG_STRIP_OFFSETS) ?: ifd.longs(TAG_TILE_OFFSETS) ?: throw TiffFormatException("IFD has no strip or tile offsets")
        val counts = ifd.longs(TAG_STRIP_BYTE_COUNTS) ?: ifd.longs(TAG_TILE_BYTE_COUNTS) ?: throw TiffFormatException("IFD has no strip or tile byte counts")
        if (offsets.size != counts.size) throw TiffFormatException("strip offsets and byte counts differ in length")
        val total = counts.sum()
        if (total > Int.MAX_VALUE) throw TiffFormatException("image data larger than 2 GiB is not supported")
        val out = ByteArray(total.toInt())
        var pos = 0
        for (i in offsets.indices) {
            raf.seek(offsets[i])
            raf.readFully(out, pos, counts[i].toInt())
            pos += counts[i].toInt()
        }
        return out
    }

    override fun close() = raf.close()

    companion object {
        const val TAG_NEW_SUBFILE_TYPE = 254
        const val TAG_IMAGE_WIDTH = 256
        const val TAG_IMAGE_LENGTH = 257
        const val TAG_BITS_PER_SAMPLE = 258
        const val TAG_COMPRESSION = 259
        const val TAG_PHOTOMETRIC = 262
        const val TAG_STRIP_OFFSETS = 273
        const val TAG_SAMPLES_PER_PIXEL = 277
        const val TAG_STRIP_BYTE_COUNTS = 279
        const val TAG_SUB_IFDS = 330
        const val TAG_TILE_OFFSETS = 324
        const val TAG_TILE_BYTE_COUNTS = 325
        const val TAG_EXIF_IFD = 34665

        fun open(file: File): TiffFile {
            val raf = RandomAccessFile(file, "r")
            try {
                val head = ByteArray(8)
                raf.readFully(head)
                val order = when {
                    head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                    head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                    else -> throw TiffFormatException("not a TIFF/DNG file (bad byte-order mark)")
                }
                val hb = ByteBuffer.wrap(head).order(order)
                if (hb.getShort(2).toInt() != 42) throw TiffFormatException("bad TIFF magic")
                val ifds = ArrayList<TiffIfd>()
                val queue = ArrayDeque<Long>().apply { add(hb.getInt(4).toLong() and 0xFFFFFFFFL) }
                val seen = HashSet<Long>()
                while (queue.isNotEmpty()) {
                    val offset = queue.removeFirst()
                    if (offset == 0L || !seen.add(offset)) continue
                    val ifd = readIfd(raf, offset, order)
                    ifds.add(ifd)
                    queue.add(ifd.nextOffset)
                    ifd.longs(TAG_SUB_IFDS)?.forEach { queue.add(it) }
                    ifd.longs(TAG_EXIF_IFD)?.forEach { queue.add(it) }
                }
                if (ifds.isEmpty()) throw TiffFormatException("no IFDs")
                return TiffFile(raf, order, ifds)
            } catch (t: Throwable) {
                raf.close()
                throw t
            }
        }

        private fun readIfd(raf: RandomAccessFile, offset: Long, order: ByteOrder): TiffIfd {
            raf.seek(offset)
            val countBytes = ByteArray(2)
            raf.readFully(countBytes)
            val n = ByteBuffer.wrap(countBytes).order(order).short.toInt() and 0xFFFF
            val raw = ByteArray(n * 12 + 4)
            raf.readFully(raw)
            val b = ByteBuffer.wrap(raw).order(order)
            val entries = LinkedHashMap<Int, TiffEntry>()
            for (i in 0 until n) {
                val base = i * 12
                val tag = b.getShort(base).toInt() and 0xFFFF
                val type = b.getShort(base + 2).toInt() and 0xFFFF
                val count = b.getInt(base + 4).toLong() and 0xFFFFFFFFL
                val size = TiffEntry.typeSize(type)
                if (size == 0) continue // unknown type: skip, as the TIFF spec requires
                val total = size.toLong() * count
                val data = if (total <= 4) {
                    raw.copyOfRange(base + 8, base + 8 + total.toInt())
                } else {
                    val valueOffset = b.getInt(base + 8).toLong() and 0xFFFFFFFFL
                    if (total > Int.MAX_VALUE || valueOffset + total > raf.length()) throw TiffFormatException("tag $tag value lies outside the file")
                    val out = ByteArray(total.toInt())
                    val saved = raf.filePointer
                    raf.seek(valueOffset)
                    raf.readFully(out)
                    raf.seek(saved)
                    out
                }
                entries[tag] = TiffEntry(tag, type, count, data, order)
            }
            val next = b.getInt(n * 12).toLong() and 0xFFFFFFFFL
            return TiffIfd(offset, entries, next)
        }
    }
}
