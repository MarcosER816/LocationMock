package com.mockroute.core.tiles

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal PMTiles v3 reader (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md) on top of a ranged
 * read function. Used to serve Overture building tiles to the map as plain z/x/y tiles, because MapLibre Android's
 * own pmtiles:// source did not show them on the device.
 *
 * [read] returns exactly [length] bytes starting at [offset] (an HTTP Range request in the app). Thread-safe:
 * directories are cached in a small synchronized LRU.
 */
class PmTilesReader(private val read: (offset: Long, length: Int) -> ByteArray) {

    class Header(
        val rootDirOffset: Long, val rootDirLength: Long,
        val leafDirOffset: Long, val tileDataOffset: Long,
        val internalCompression: Int, val tileCompression: Int, val tileType: Int,
        val minZoom: Int, val maxZoom: Int,
    )

    class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Long)

    @Volatile private var header: Header? = null
    private val dirCache = object : LinkedHashMap<Long, List<Entry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>?) = size > 48
    }

    fun header(): Header = header ?: synchronized(this) {
        header ?: parseHeader(read(0, 16384)).also { h ->
            header = h
            // The root directory usually sits in the first 16 KiB; read it separately otherwise.
            synchronized(dirCache) { dirCache[-1] = decodeDirectory(decompress(read(h.rootDirOffset, h.rootDirLength.toInt()), h.internalCompression)) }
        }
    }

    /** The tile's bytes, decompressed (gzip removed), or null if the archive has no tile there. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val h = header()
        if (z < h.minZoom || z > h.maxZoom) return null
        val id = zxyToTileId(z, x, y)
        var dirKey = -1L
        var dir = synchronized(dirCache) { dirCache[-1] } ?: return null
        repeat(4) {
            val e = findEntry(dir, id) ?: return null
            if (e.runLength > 0) {
                return decompress(read(h.tileDataOffset + e.offset, e.length), h.tileCompression)
            }
            // Leaf directory.
            dirKey = h.leafDirOffset + e.offset
            dir = synchronized(dirCache) { dirCache[dirKey] } ?: decodeDirectory(
                decompress(read(dirKey, e.length), h.internalCompression),
            ).also { d -> synchronized(dirCache) { dirCache[dirKey] = d } }
        }
        return null
    }

    companion object {
        fun parseHeader(b: ByteArray): Header {
            require(b.size >= 127 && String(b, 0, 7, Charsets.US_ASCII) == "PMTiles" && b[7].toInt() == 3) { "not a PMTiles v3 archive" }
            fun u64(at: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF); return v }
            return Header(
                rootDirOffset = u64(8), rootDirLength = u64(16),
                leafDirOffset = u64(40), tileDataOffset = u64(56),
                internalCompression = b[97].toInt(), tileCompression = b[98].toInt(), tileType = b[99].toInt(),
                minZoom = b[100].toInt() and 0xFF, maxZoom = b[101].toInt() and 0xFF,
            )
        }

        /** 1 = none, 2 = gzip (0 = unknown, treated as gzip-if-it-looks-like-gzip). */
        fun decompress(data: ByteArray, compression: Int): ByteArray {
            val gz = data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()
            if (compression == 1 || !gz) return data
            GZIPInputStream(ByteArrayInputStream(data)).use { input ->
                val out = ByteArrayOutputStream(data.size * 3)
                input.copyTo(out)
                return out.toByteArray()
            }
        }

        fun decodeDirectory(b: ByteArray): List<Entry> {
            var pos = 0
            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    val byte = b[pos++].toInt() and 0xFF
                    result = result or ((byte and 0x7F).toLong() shl shift)
                    if (byte and 0x80 == 0) return result
                    shift += 7
                }
            }
            val n = varint().toInt()
            val ids = LongArray(n)
            var last = 0L
            for (i in 0 until n) { last += varint(); ids[i] = last }
            val runs = LongArray(n) { varint() }
            val lengths = IntArray(n) { varint().toInt() }
            val offsets = LongArray(n)
            for (i in 0 until n) {
                val v = varint()
                offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
            }
            return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
        }

        /** Entry covering [id]: exact match, or the previous entry if its run (or leaf, run 0) reaches it. */
        fun findEntry(entries: List<Entry>, id: Long): Entry? {
            var lo = 0
            var hi = entries.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val c = entries[mid].tileId.compareTo(id)
                when {
                    c < 0 -> lo = mid + 1
                    c > 0 -> hi = mid - 1
                    else -> return entries[mid]
                }
            }
            if (hi >= 0) {
                val e = entries[hi]
                if (e.runLength == 0L) return e // leaf directory: may contain the tile
                if (id - e.tileId < e.runLength) return e
            }
            return null
        }

        /** Hilbert-curve tile id (PMTiles spec). */
        fun zxyToTileId(z: Int, x: Int, y: Int): Long {
            var acc = 0L
            for (t in 0 until z) acc += (1L shl t) * (1L shl t)
            val n = 1L shl z
            var rx: Long
            var ry: Long
            var d = 0L
            var tx = x.toLong()
            var ty = y.toLong()
            var s = n / 2
            while (s > 0) {
                rx = if (tx and s > 0) 1 else 0
                ry = if (ty and s > 0) 1 else 0
                d += s * s * ((3 * rx) xor ry)
                // rotate
                if (ry == 0L) {
                    if (rx == 1L) {
                        tx = s - 1 - tx
                        ty = s - 1 - ty
                    }
                    val tmp = tx; tx = ty; ty = tmp
                }
                s /= 2
            }
            return acc + d
        }
    }
}
