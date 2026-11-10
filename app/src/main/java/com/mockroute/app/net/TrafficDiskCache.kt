package com.mockroute.app.net

import android.content.Context
import com.mockroute.core.traffic.SpeedProfileSample
import com.mockroute.core.traffic.TrafficCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Traffic samples on disk, keyed by (route hash, weekday, 15-min bin); 7-day TTL; ≤ 2000 files. */
class TrafficDiskCache(context: Context) : TrafficCacheStore {
    private val dir = File(context.cacheDir, "traffic").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun get(key: String): SpeedProfileSample? = withContext(Dispatchers.IO) {
        val f = File(dir, "$key.json")
        if (!f.exists()) return@withContext null
        if (System.currentTimeMillis() - f.lastModified() > TTL_MS) {
            f.delete()
            return@withContext null
        }
        runCatching { json.decodeFromString(SpeedProfileSample.serializer(), f.readText()) }.onFailure { f.delete() }.getOrNull()
    }

    override suspend fun put(key: String, sample: SpeedProfileSample) = withContext(Dispatchers.IO) {
        runCatching {
            File(dir, "$key.json").writeText(json.encodeToString(SpeedProfileSample.serializer(), sample))
            val files = dir.listFiles().orEmpty()
            if (files.size > MAX_FILES) files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
        }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }

    companion object {
        private const val TTL_MS = 7L * 24 * 3600 * 1000
        private const val MAX_FILES = 2000
    }
}
