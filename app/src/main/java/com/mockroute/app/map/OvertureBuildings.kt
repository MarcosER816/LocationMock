package com.mockroute.app.map

import android.content.Context
import com.mockroute.app.net.HttpJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Overture Maps building tiles (PMTiles on S3, free, no key). They add footprints traced from imagery where
 * OpenStreetMap has none (e.g. Colonia Héroes de Churubusco, CDMX).
 *
 * Overture publishes a new release about every month and deletes old ones after ~2 months, so the URL can't be
 * hard-coded: [refresh] asks Overture's STAC catalog for the latest release at most once a day and keeps it in
 * filesDir/overture.json; [styleWith] puts it into the bundled style (placeholder written by
 * tools/make_map_style.py).
 */
object OvertureBuildings {
    /** Release known when this build was made; used until the first successful [refresh]. */
    const val DEFAULT_RELEASE = "2026-10-21.0"
    private const val CATALOG = "https://stac.overturemaps.org/catalog.json"
    private const val PLACEHOLDER = "OVERTURE_PLACEHOLDER"
    private const val REFRESH_MS = 24L * 3600 * 1000

    fun urlFor(release: String) =
        "https://overturemaps-extras-us-west-2.s3.us-west-2.amazonaws.com/tiles/$release/buildings.pmtiles"

    private fun file(context: Context) = File(context.filesDir, "overture.json")

    /** Last known release (fast, no network). */
    fun cachedRelease(context: Context): String = runCatching {
        JSONObject(file(context).readText()).getString("release").takeIf { it.isNotBlank() }
    }.getOrNull() ?: DEFAULT_RELEASE

    /** Asks the catalog for the latest release (at most once a day). Returns it, or null if nothing changed/failed. */
    suspend fun refresh(context: Context): String? {
        val f = file(context)
        val checkedAt = runCatching { JSONObject(f.readText()).optLong("checkedAt") }.getOrDefault(0L)
        if (System.currentTimeMillis() - checkedAt < REFRESH_MS) return null
        val latest = runCatching {
            val r = HttpJson.get(CATALOG)
            if (r.code in 200..299) JSONObject(r.body ?: "").optString("latest").takeIf { it.matches(Regex("[0-9][0-9.-]+")) } else null
        }.getOrNull() ?: return null
        val old = cachedRelease(context)
        runCatching { f.writeText(JSONObject().put("release", latest).put("checkedAt", System.currentTimeMillis()).toString()) }
        return latest.takeIf { it != old }
    }

    /**
     * The bundled style with Overture's URL filled in. With Overture present, the OpenMapTiles 3-D layer is
     * dropped (Overture already holds those buildings; two extrusions of one building would flicker). The
     * OpenMapTiles flat footprints stay underneath as a fallback if Overture can't be reached.
     */
    fun styleWith(styleJson: String, release: String?): String = runCatching {
        val style = JSONObject(styleJson)
        val layers = style.getJSONArray("layers")
        val kept = JSONArray()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            val drop = if (release == null) layer.optString("source") == "overture" else layer.optString("id") == "building-3d"
            if (!drop) kept.put(layer)
        }
        style.put("layers", kept)
        val sources = style.getJSONObject("sources")
        if (release == null) sources.remove("overture")
        else sources.getJSONObject("overture").put("url", "pmtiles://" + urlFor(release))
        style.toString()
    }.getOrElse { styleJson.replace(PLACEHOLDER, urlFor(release ?: DEFAULT_RELEASE)) }
}
