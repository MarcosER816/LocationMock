package com.mockroute.core.timeline

import com.mockroute.core.trajectory.Trajectory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** File envelope. `format` identifies MockRoute timelines; `version` drives migrations. */
@Serializable
data class TimelineFile(val format: String = FORMAT, val version: Int = TimelineCodec.VERSION, val timeline: Timeline) {
    companion object {
        const val FORMAT = "mockroute-timeline"
    }
}

class TimelineFormatException(message: String) : Exception(message)

/**
 * JSON (de)serialisation of timelines. Version history:
 * - **1** (Stage 8, 2026-11-10): first format. Segments are a polymorphic list with discriminator `"type"`
 *   (`route`, `indoor`, `street`, `pause`).
 * Migrations: [MIGRATIONS] maps version v → a function upgrading the raw JSON to v + 1. None needed yet.
 */
object TimelineCodec {
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        classDiscriminator = "type"
    }

    /** v → upgrade raw JSON text from v to v + 1. */
    private val MIGRATIONS: Map<Int, (String) -> String> = emptyMap()

    fun encode(t: Timeline): String = json.encodeToString(TimelineFile.serializer(), TimelineFile(timeline = t))

    fun decode(text: String): Timeline {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw TimelineFormatException("Not a JSON file") }
        val format = root["format"]?.jsonPrimitive?.content
        if (format != TimelineFile.FORMAT) throw TimelineFormatException("Not a MockRoute timeline")
        var version = root["version"]?.jsonPrimitive?.int ?: throw TimelineFormatException("Missing version")
        if (version > VERSION) throw TimelineFormatException("Made by a newer MockRoute (format v$version); update the app")
        var body = text
        while (version < VERSION) {
            body = MIGRATIONS[version]?.invoke(body) ?: throw TimelineFormatException("No migration from v$version")
            version++
        }
        return runCatching { json.decodeFromString(TimelineFile.serializer(), body).timeline.normalized() }
            .getOrElse { throw TimelineFormatException("Damaged timeline: ${it.message}") }
    }
}

/** GPX 1.1 track of a trajectory sampled every [stepS] seconds, with timestamps starting at [startEpochS]. */
object Gpx {
    fun write(name: String, trajectory: Trajectory, startEpochS: Long, stepS: Double = 1.0): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<gpx version="1.1" creator="MockRoute" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        sb.append("  <trk><name>").append(escape(name)).append("</name><trkseg>\n")
        var t = 0.0
        val end = trajectory.durationSeconds ?: 0.0
        while (true) {
            val s = trajectory.stateAt(t)
            val time = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(((startEpochS + t) * 1000).toLong()))
            sb.append(String.format(Locale.US, "    <trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele><time>%s</time></trkpt>\n", s.lat, s.lon, s.altitudeM, time))
            if (t >= end) break
            t = minOf(end, t + stepS)
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
