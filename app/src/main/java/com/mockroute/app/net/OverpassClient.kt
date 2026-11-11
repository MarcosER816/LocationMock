package com.mockroute.app.net

import android.content.Context
import android.util.Log
import com.mockroute.core.streets.Overpass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class OverpassException(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

/**
 * Overpass with a 30-day disk cache (save time stored in a side file, never File.lastModified()), descriptive
 * User-Agent, backoff on 429/504 (2 s, 6 s), a second public endpoint on network failure, and a hard cap of
 * [DAILY_LIMIT] network queries per day (counted by the caller via [onRequest]).
 */
class OverpassClient(context: Context) {
    private val dir = File(context.cacheDir, "overpass").apply { mkdirs() }

    /** Cached body for [query] if younger than 30 days. */
    suspend fun cached(query: String): Pair<String, Long>? = withContext(Dispatchers.IO) {
        val key = Overpass.cacheKey(query)
        val body = File(dir, "$key.json")
        val meta = File(dir, "$key.meta")
        if (!body.exists() || !meta.exists()) return@withContext null
        val savedAt = meta.readText().trim().toLongOrNull() ?: return@withContext null
        if (System.currentTimeMillis() - savedAt > TTL_MS) {
            body.delete()
            meta.delete()
            return@withContext null
        }
        body.readText() to savedAt
    }

    /** Network fetch (no cache read); stores the result in the cache. */
    suspend fun fetch(query: String, requestsToday: Int, onRequest: suspend () -> Unit): String {
        if (requestsToday >= DAILY_LIMIT) {
            throw OverpassException("Overpass limit reached ($DAILY_LIMIT queries today). Cached roads still work; try tomorrow.")
        }
        var lastError: OverpassException? = null
        for (endpoint in Overpass.ENDPOINTS) {
            for (backoff in listOf(0L, 2_000L, 6_000L)) {
                if (backoff > 0) delay(backoff)
                onRequest()
                try {
                    val (code, body) = post(endpoint, query)
                    when {
                        code in 200..299 && body != null -> {
                            store(query, body)
                            return body
                        }
                        code == 429 || code == 504 -> lastError = OverpassException("Overpass is busy (HTTP $code). Wait a minute and tap Refresh roads.")
                        code == 400 -> throw OverpassException("Overpass rejected the query (HTTP 400). Try a simpler shape.")
                        else -> {
                            lastError = OverpassException("Overpass error HTTP $code.")
                            break // try the next endpoint
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Overpass $endpoint failed", e)
                    lastError = OverpassException("Can't reach Overpass (offline?).", e)
                    break // next endpoint
                }
            }
        }
        throw lastError ?: OverpassException("Overpass failed.")
    }

    private suspend fun post(endpoint: String, query: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        // OkHttp + BlockSafeDns (via HttpJson.slowClient): works even when the phone's DNS blocks the host.
        val req = okhttp3.Request.Builder().url(endpoint)
            .post(okhttp3.FormBody.Builder(Charsets.UTF_8).add("data", query).build())
            .header("User-Agent", Overpass.USER_AGENT)
            .header("Accept", "application/json")
            .build()
        val r = HttpJson.execute(HttpJson.slowClient, req)
        r.code to r.body
    }

    private suspend fun store(query: String, body: String) = withContext(Dispatchers.IO) {
        runCatching {
            val key = Overpass.cacheKey(query)
            File(dir, "$key.json").writeText(body)
            File(dir, "$key.meta").writeText(System.currentTimeMillis().toString())
        }
    }

    companion object {
        private const val TAG = "OverpassClient"
        const val DAILY_LIMIT = 100
        private const val TTL_MS = 30L * 24 * 3600 * 1000
    }
}
