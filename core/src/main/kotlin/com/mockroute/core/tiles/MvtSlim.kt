package com.mockroute.core.tiles

import java.io.ByteArrayOutputStream

/**
 * Rewrites a Mapbox Vector Tile keeping only the feature properties in [keep] (geometry, type and id untouched).
 *
 * Overture building tiles carry a unique GERS id, a JSON "sources" list, names etc. for every building, which makes
 * a dense z14 tile ~1.8 MB; the map only needs heights. Slimming them makes tiles several times smaller for
 * MapLibre to parse and hold, so the buildings keep up when the camera moves.
 *
 * Protobuf (MVT 2.1): Tile{ 3: Layer* } ; Layer{ 1 name, 2 Feature*, 3 keys, 4 values (Value msg), 5 extent,
 * 15 version } ; Feature{ 1 id, 2 tags (packed uint32: key,value,…), 3 type, 4 geometry (packed) }.
 */
object MvtSlim {
    fun slim(tile: ByteArray, keep: Set<String>): ByteArray {
        val out = ByteArrayOutputStream(tile.size / 3)
        val r = Reader(tile, 0, tile.size)
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == 3 && wire == 2) {
                val (s, e) = r.lengthDelimited()
                writeBytesField(out, 3, slimLayer(tile, s, e, keep))
            } else {
                r.copyField(field, wire, out)
            }
        }
        return out.toByteArray()
    }

    private class Feature(val start: Int, val end: Int)

    private fun slimLayer(buf: ByteArray, start: Int, end: Int, keep: Set<String>): ByteArray {
        val keys = ArrayList<String>()
        val values = ArrayList<Pair<Int, Int>>() // byte ranges of Value messages
        val features = ArrayList<Feature>()
        val other = ByteArrayOutputStream()
        val r = Reader(buf, start, end)
        while (r.more()) {
            val (field, wire) = r.tag()
            when {
                field == 3 && wire == 2 -> { val (s, e) = r.lengthDelimited(); keys += String(buf, s, e - s, Charsets.UTF_8) }
                field == 4 && wire == 2 -> values += r.lengthDelimited()
                field == 2 && wire == 2 -> { val (s, e) = r.lengthDelimited(); features += Feature(s, e) }
                else -> r.copyField(field, wire, other) // name, extent, version
            }
        }
        // New key table: only kept keys; new value table: only values still referenced.
        val keyMap = IntArray(keys.size) { -1 }
        val newKeys = ArrayList<String>()
        keys.forEachIndexed { i, k -> if (k in keep) { keyMap[i] = newKeys.size; newKeys += k } }
        val valueMap = HashMap<Int, Int>()
        val newValues = ArrayList<Pair<Int, Int>>()

        val out = ByteArrayOutputStream()
        out.write(other.toByteArray())
        for (f in features) {
            val fr = Reader(buf, f.start, f.end)
            val fo = ByteArrayOutputStream()
            while (fr.more()) {
                val (field, wire) = fr.tag()
                if (field == 2 && wire == 2) {
                    val (s, e) = fr.lengthDelimited()
                    val tr = Reader(buf, s, e)
                    val tags = ArrayList<Int>()
                    while (tr.more()) {
                        val k = tr.varint().toInt()
                        val v = tr.varint().toInt()
                        val nk = keyMap.getOrElse(k) { -1 }
                        if (nk < 0 || v >= values.size) continue
                        val nv = valueMap.getOrPut(v) { newValues += values[v]; newValues.size - 1 }
                        tags += nk; tags += nv
                    }
                    if (tags.isNotEmpty()) {
                        val packed = ByteArrayOutputStream()
                        tags.forEach { writeVarint(packed, it.toLong()) }
                        writeBytesField(fo, 2, packed.toByteArray())
                    }
                } else {
                    fr.copyField(field, wire, fo)
                }
            }
            writeBytesField(out, 2, fo.toByteArray())
        }
        newKeys.forEach { writeBytesField(out, 3, it.toByteArray(Charsets.UTF_8)) }
        newValues.forEach { (s, e) -> writeBytesField(out, 4, buf.copyOfRange(s, e)) }
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, v0: Long) {
        var v = v0
        while (v and 0x7FL.inv() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }

    private fun writeBytesField(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
        writeVarint(out, (field.toLong() shl 3) or 2)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        fun tag(): Pair<Int, Int> { val t = varint(); return (t ushr 3).toInt() to (t and 7).toInt() }
        fun lengthDelimited(): Pair<Int, Int> { val len = varint().toInt(); val s = pos; pos += len; return s to pos }

        /** Copies the field whose tag was just read (tag re-encoded) to [out]. */
        fun copyField(field: Int, wire: Int, out: ByteArrayOutputStream) {
            writeVarint(out, (field.toLong() shl 3) or wire.toLong())
            when (wire) {
                0 -> writeVarint(out, varint())
                1 -> { out.write(buf, pos, 8); pos += 8 }
                2 -> { val (s, e) = lengthDelimited(); writeVarint(out, (e - s).toLong()); out.write(buf, s, e - s) }
                5 -> { out.write(buf, pos, 4); pos += 4 }
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
        }
    }
}
