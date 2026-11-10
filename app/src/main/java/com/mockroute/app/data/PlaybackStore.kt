package com.mockroute.app.data

import android.content.Context
import java.io.File

/**
 * Where a trip's playback was, saved every few seconds while it runs. A clean stop deletes it; if the process is
 * killed it stays, and the app offers "Resume trip" on the next start.
 */
class PlaybackStore(context: Context) {
    data class Saved(val name: String, val elapsedS: Double, val totalS: Double, val savedAtMs: Long)

    private val file = File(context.filesDir, "playback.json")

    fun save(s: Saved) {
        // Plain lines (the app module has no serialization plugin): elapsed, total, savedAt, then the name.
        runCatching { file.writeText("${s.elapsedS}\n${s.totalS}\n${s.savedAtMs}\n${s.name}") }
    }

    fun load(): Saved? = runCatching {
        if (!file.exists()) return null
        val l = file.readText().split("\n", limit = 4)
        Saved(l[3], l[0].toDouble(), l[1].toDouble(), l[2].toLong())
    }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }
}
