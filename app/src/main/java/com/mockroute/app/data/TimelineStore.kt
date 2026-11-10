package com.mockroute.app.data

import android.content.Context
import com.mockroute.core.timeline.Timeline
import com.mockroute.core.timeline.TimelineCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Saved timelines as versioned JSON files in filesDir/timelines (see [TimelineCodec]); plus the current one. */
class TimelineStore(context: Context) {
    private val dir = File(context.filesDir, "timelines").apply { mkdirs() }
    private val current = File(dir, ".current.json")

    data class Entry(val name: String, val file: File, val modifiedMs: Long)

    suspend fun list(): List<Entry> = withContext(Dispatchers.IO) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith(".") }.orEmpty()
            .map { Entry(it.name.removeSuffix(".json"), it, it.lastModified()) }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun save(t: Timeline): String = withContext(Dispatchers.IO) {
        val safe = t.name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().ifEmpty { "Timeline" }.take(60)
        File(dir, "$safe.json").writeText(TimelineCodec.encode(t))
        safe
    }

    suspend fun load(name: String): Timeline = withContext(Dispatchers.IO) { TimelineCodec.decode(File(dir, "$name.json").readText()) }

    suspend fun delete(name: String) = withContext(Dispatchers.IO) { File(dir, "$name.json").delete() }

    suspend fun saveCurrent(t: Timeline) = withContext(Dispatchers.IO) { runCatching { current.writeText(TimelineCodec.encode(t)) } }

    suspend fun loadCurrent(): Timeline? = withContext(Dispatchers.IO) {
        if (!current.exists()) null else runCatching { TimelineCodec.decode(current.readText()) }.getOrNull()
    }

    /** A file in the share cache for exporting (shared through the FileProvider). */
    fun exportFile(name: String, ext: String, content: String): File {
        val out = File(File(dir.parentFile, "exports").apply { mkdirs() }, "${name.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.$ext")
        out.writeText(content)
        return out
    }
}
