package com.mockroute.app.net

import android.content.Context
import android.util.Log
import com.mockroute.core.traffic.CachedSample
import com.mockroute.core.traffic.SpeedProfileSample
import com.mockroute.core.traffic.TrafficCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Traffic samples on disk, keyed by (route hash, weekday, 15-min bin); 7-day TTL; ≤ 2000 files.
 *
 * The save time is stored INSIDE each file: an earlier version relied on File.lastModified(), and on the phone
 * every entry looked expired (cache never hit).
 */
class TrafficDiskCache(context: Context) : TrafficCacheStore {
    private val dir = File(context.cacheDir, "traffic").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    /** Last read/write problem (shown in the traffic row for diagnosis), or null. */
    @Volatile var lastProblem: String? = null
        private set

    override suspend fun get(key: String): SpeedProfileSample? = withContext(Dispatchers.IO) {
        val f = File(dir, "$key.json")
        if (!f.exists()) return@withContext null
        val entry = runCatching { json.decodeFromString(CachedSample.serializer(), f.readText()) }
            .onFailure {
                lastProblem = "read: ${it.javaClass.simpleName}"
                Log.w(TAG, "unreadable cache entry $key", it)
                f.delete()
            }
            .getOrNull() ?: return@withContext null
        if (System.currentTimeMillis() - entry.savedAtMs > TTL_MS) {
            f.delete()
            return@withContext null
        }
        entry.sample
    }

    override suspend fun put(key: String, sample: SpeedProfileSample) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            File(dir, "$key.json").writeText(json.encodeToString(CachedSample.serializer(), CachedSample(System.currentTimeMillis(), sample)))
            val files = dir.listFiles().orEmpty()
            if (files.size > MAX_FILES) files.sortedBy { it.name }.take(files.size - MAX_FILES).forEach { it.delete() }
        }.onFailure {
            lastProblem = "write: ${it.javaClass.simpleName}"
            Log.w(TAG, "cache write failed", it)
        }
        Unit
    }

    fun entryCount(): Int = dir.listFiles()?.size ?: 0

    suspend fun clear() = withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }

    companion object {
        private const val TAG = "TrafficDiskCache"
        private const val TTL_MS = 7L * 24 * 3600 * 1000
        private const val MAX_FILES = 2000
    }
}
