package com.mockroute.core.tiles

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PmTilesTest {
    @Test
    fun `tile ids follow the spec's Hilbert order`() {
        assertEquals(0L, PmTilesReader.zxyToTileId(0, 0, 0))
        assertEquals(1L, PmTilesReader.zxyToTileId(1, 0, 0))
        assertEquals(2L, PmTilesReader.zxyToTileId(1, 0, 1))
        assertEquals(3L, PmTilesReader.zxyToTileId(1, 1, 1))
        assertEquals(4L, PmTilesReader.zxyToTileId(1, 1, 0))
        assertEquals(5L, PmTilesReader.zxyToTileId(2, 0, 0))
        // Spec test vectors (pmtiles js): z12 and z20.
        assertEquals(19078479L, PmTilesReader.zxyToTileId(12, 3423, 1763))
    }

    private fun varint(out: ByteArrayOutputStream, v0: Long) {
        var v = v0
        while (v >= 0x80) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }

    @Test
    fun `directory decodes delta ids, runs, lengths and contiguous offsets`() {
        val out = ByteArrayOutputStream()
        // 3 entries: ids 5, 6, 300; runs 1, 0 (leaf), 2; lengths 10, 20, 300; offsets 0, contiguous, 1000.
        varint(out, 3)
        listOf(5L, 1L, 294L).forEach { varint(out, it) }
        listOf(1L, 0L, 2L).forEach { varint(out, it) }
        listOf(10L, 20L, 300L).forEach { varint(out, it) }
        listOf(1L, 0L, 1001L).forEach { varint(out, it) }
        val d = PmTilesReader.decodeDirectory(out.toByteArray())
        assertEquals(listOf(5L, 6L, 300L), d.map { it.tileId })
        assertEquals(listOf(0L, 10L, 1000L), d.map { it.offset })
        assertEquals(6L, PmTilesReader.findEntry(d, 6)!!.tileId)
        assertEquals(6L, PmTilesReader.findEntry(d, 200)!!.tileId) // inside leaf range
        assertEquals(300L, PmTilesReader.findEntry(d, 301)!!.tileId) // run of 2
        assertNull(PmTilesReader.findEntry(d, 302))
        assertNull(PmTilesReader.findEntry(d, 4))
    }

    /** Live check against Overture's archive: run with PMTILES_LIVE=<url> (not in CI). */
    @Test
    fun `live overture tile`() {
        val url = System.getenv("PMTILES_LIVE") ?: return
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        var requests = 0
        val reader = PmTilesReader { off, len ->
            requests++
            http.send(
                HttpRequest.newBuilder(URI(url)).header("Range", "bytes=$off-${off + len - 1}").build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            ).body()
        }
        // Colonia Héroes de Churubusco, CDMX, z14 and z12.
        for ((z, x, y) in listOf(Triple(14, 3681, 7293), Triple(12, 920, 1823), Triple(13, 1840, 3646))) {
            val t = reader.tile(z, x, y)
            assertNotNull(t, "tile $z/$x/$y")
            assertTrue(t.size > 1000)
            val md5 = MessageDigest.getInstance("MD5").digest(t).joinToString("") { "%02x".format(it) }
            println("tile $z/$x/$y: ${t.size} bytes md5 $md5")
        }
        println("requests: $requests")
    }
}
