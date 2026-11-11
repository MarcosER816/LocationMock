package com.mockroute.core.tiles

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MvtSlimTest {
    private fun varint(o: ByteArrayOutputStream, v0: Long) { var v = v0; while (v >= 0x80) { o.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }; o.write(v.toInt()) }
    private fun field(o: ByteArrayOutputStream, f: Int, b: ByteArray) { varint(o, (f.toLong() shl 3) or 2); varint(o, b.size.toLong()); o.write(b) }
    private fun bytes(block: (ByteArrayOutputStream) -> Unit) = ByteArrayOutputStream().also(block).toByteArray()

    @Test
    fun `keeps geometry and wanted keys, drops the rest`() {
        val geom = bytes { listOf(9L, 50L, 34L).forEach { v -> varint(it, v) } }
        val strVal = { s: String -> bytes { field(it, 1, s.toByteArray()) } }
        val dblVal = bytes { varint(it, (3L shl 3) or 1); it.write(ByteArray(8) { 0 }.also { b -> b[7] = 0x40 }) } // 2.0
        val feature = { tags: List<Long> ->
            bytes { o ->
                varint(o, (1L shl 3) or 0); varint(o, 7)
                field(o, 2, bytes { t -> tags.forEach { varint(t, it) } })
                varint(o, (3L shl 3) or 0); varint(o, 3)
                field(o, 4, geom)
            }
        }
        val layer = bytes { o ->
            field(o, 1, "building".toByteArray())
            field(o, 2, feature(listOf(0, 0, 1, 1))) // id=uuid-a, height=2.0
            field(o, 2, feature(listOf(0, 2)))       // id=uuid-b only
            field(o, 3, "id".toByteArray()); field(o, 3, "height".toByteArray())
            field(o, 4, strVal("uuid-a")); field(o, 4, dblVal); field(o, 4, strVal("uuid-b"))
            varint(o, (5L shl 3) or 0); varint(o, 4096)
            varint(o, (15L shl 3) or 0); varint(o, 2)
        }
        val tile = bytes { field(it, 3, layer) }
        val slim = MvtSlim.slim(tile, setOf("height"))
        assertTrue(slim.size < tile.size)
        val s = String(slim, Charsets.ISO_8859_1)
        assertFalse("uuid-a" in s || "uuid-b" in s)
        assertTrue("height" in s && "building" in s)
        // Geometry bytes survive unchanged, twice.
        assertEquals(2, Regex(Regex.escape(String(geom, Charsets.ISO_8859_1))).findAll(s).count())
    }

    /** PMTILES_LIVE=<url>: slims a real Overture tile and writes it to $SLIM_OUT for checking with another decoder. */
    @Test
    fun `live slim`() {
        val url = System.getenv("PMTILES_LIVE") ?: return
        val http = HttpClient.newHttpClient()
        val reader = PmTilesReader { off, len ->
            http.send(HttpRequest.newBuilder(URI(url)).header("Range", "bytes=$off-${off + len - 1}").build(), HttpResponse.BodyHandlers.ofByteArray()).body()
        }
        val t = reader.tile(14, 3681, 7293)!!
        val t0 = System.nanoTime()
        val slim = MvtSlim.slim(t, setOf("height", "num_floors", "min_height", "is_underground", "has_parts"))
        println("slim: ${t.size} -> ${slim.size} bytes in ${(System.nanoTime() - t0) / 1_000_000} ms")
        System.getenv("SLIM_OUT")?.let { File(it).writeBytes(slim) }
    }
}
