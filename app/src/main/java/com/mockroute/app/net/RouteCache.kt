package com.mockroute.app.net

import android.content.Context
import android.util.Log
import com.mockroute.core.geo.LatLon
import com.mockroute.core.route.CachedRoute
import com.mockroute.core.route.RouteResult
import com.mockroute.core.route.RoutingProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Disk cache of successful routing responses keyed by (provider, profile, points rounded to ~0.1 m).
 * Entries live 30 days; at most [MAX_ENTRIES] files (oldest evicted). Fallback results are never cached.
 */
class RouteCache(context: Context) {
    private val dir = File(context.cacheDir, "routes").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun key(providerId: String, profile: RoutingProfile, points: List<LatLon>): String {
        val raw = buildString {
            // "v2": 1.2.0 routes carry the provider's per-street speeds; older cache entries don't.
            append("v2|").append(providerId).append('|').append(profile.name)
            points.forEach { append('|').append(String.format(Locale.US, "%.6f,%.6f", it.lat, it.lon)) }
        }
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** Save time stored inside the file (File.lastModified() proved unreliable on the phone). */
    suspend fun get(key: String): RouteResult? = withContext(Dispatchers.IO) {
        val f = File(dir, "$key.json")
        if (!f.exists()) return@withContext null
        val e = runCatching { json.decodeFromString(CachedRoute.serializer(), f.readText()) }
            .onFailure { f.delete() }
            .getOrNull() ?: return@withContext null
        if (System.currentTimeMillis() - e.savedAtMs > TTL_MS) {
            f.delete()
            return@withContext null
        }
        e.result
    }

    suspend fun put(key: String, result: RouteResult) = withContext(Dispatchers.IO) {
        if (result.isFallback) return@withContext
        runCatching {
            File(dir, "$key.json").writeText(json.encodeToString(CachedRoute.serializer(), CachedRoute(System.currentTimeMillis(), result)))
            val files = dir.listFiles().orEmpty()
            if (files.size > MAX_ENTRIES) {
                files.sortedBy { it.name }.take(files.size - MAX_ENTRIES).forEach { it.delete() }
            }
        }.onFailure { Log.w("RouteCache", "write failed", it) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }

    fun entryCount(): Int = dir.listFiles()?.size ?: 0

    companion object {
        private const val MAX_ENTRIES = 500
        private const val TTL_MS = 30L * 24 * 3600 * 1000
    }
}
