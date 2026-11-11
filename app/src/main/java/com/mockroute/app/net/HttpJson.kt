package com.mockroute.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal JSON-over-HTTPS client (routing, traffic, Overpass). Never logs headers: they may hold API keys.
 *
 * 1.1.7: OkHttp with [BlockSafeDns] instead of HttpURLConnection, so a phone DNS that answers 0.0.0.0 (ad-blocking
 * Private DNS, blocker app, carrier filter) no longer breaks routing; the user's phone does this.
 */
object HttpJson {
    data class Response(val code: Int, val body: String?)

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(BlockSafeDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** For slow servers (Overpass). */
    val slowClient: OkHttpClient = client.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    private const val UA = "MockRoute-Android"

    @Throws(IOException::class)
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Response = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", UA)
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        execute(client, req)
    }

    @Throws(IOException::class)
    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): Response = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json, application/geo+json").header("User-Agent", UA)
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        execute(client, req)
    }

    /** Runs [req] on [c]; IllegalArgumentException (bad URL/header) becomes IOException like before. */
    @Throws(IOException::class)
    fun execute(c: OkHttpClient, req: Request): Response = try {
        c.newCall(req).execute().use { r -> Response(r.code, r.body?.string()) }
    } catch (e: IllegalArgumentException) {
        throw IOException(e.message, e)
    }
}
