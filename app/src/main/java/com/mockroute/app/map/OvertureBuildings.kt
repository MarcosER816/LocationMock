package com.mockroute.app.map

import android.content.Context
import com.mockroute.app.net.HttpJson
import com.mockroute.core.tiles.PmTilesReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Overture Maps building tiles (one big PMTiles archive on S3, free, no key). They add footprints traced from
 * imagery where OpenStreetMap has none (e.g. Colonia Héroes de Churubusco, CDMX).
 *
 * 1.1.3: MapLibre Android's own `pmtiles://` source showed nothing on the device, so the app reads the archive
 * itself ([PmTilesReader], HTTP Range requests) and hands the map ordinary z/x/y tiles: the style asks for
 * `https://overture-tiles.mockroute.invalid/<release>/{z}/{x}/{y}.pbf`, and [interceptor] (installed in MapLibre's
 * OkHttp client by [install]) answers those requests without touching the network for that fake host.
 *
 * Overture publishes a release about every month and deletes old ones after ~2 months: [refresh] asks the STAC
 * catalog for the latest release at most once a day (filesDir/overture.json).
 */
object OvertureBuildings {
    /** Release known when this build was made; used until the first successful [refresh]. */
    const val DEFAULT_RELEASE = "2026-10-21.0"
    private const val CATALOG = "https://stac.overturemaps.org/catalog.json"
    private const val REFRESH_MS = 24L * 3600 * 1000
    const val HOST = "overture-tiles.mockroute.invalid"

    fun archiveUrl(release: String) =
        "https://overturemaps-extras-us-west-2.s3.us-west-2.amazonaws.com/tiles/$release/buildings.pmtiles"

    fun tilesUrl(release: String) = "https://$HOST/$release/{z}/{x}/{y}.pbf"

    // ---- diagnostics (Settings → Map) -------------------------------------------------------------------------
    private val served = AtomicInteger()
    private val _status = MutableStateFlow("Overture buildings: not used yet")
    val status: StateFlow<String> = _status

    private fun report(error: String? = null) {
        _status.value = if (error != null) "Overture buildings: error — $error (${served.get()} tiles OK so far)"
        else "Overture buildings: ${served.get()} tiles loaded"
    }

    // ---- release ----------------------------------------------------------------------------------------------
    private fun file(context: Context) = File(context.filesDir, "overture.json")

    fun cachedRelease(context: Context): String = runCatching {
        JSONObject(file(context).readText()).getString("release").takeIf { it.isNotBlank() }
    }.getOrNull() ?: DEFAULT_RELEASE

    /** Asks the catalog for the latest release (at most once a day). Returns it if it changed, else null. */
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

    // ---- style ------------------------------------------------------------------------------------------------
    /**
     * The bundled style with the Overture tiles URL filled in. With Overture present, the OpenMapTiles 3-D layer is
     * dropped (Overture already holds those buildings; two extrusions of one building would flicker); the
     * OpenMapTiles flat footprints stay underneath. [release] null = style without Overture (fallback).
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
        if (release == null) {
            sources.remove("overture")
        } else {
            val src = sources.getJSONObject("overture")
            src.remove("url")
            src.put("tiles", JSONArray().put(tilesUrl(release)))
        }
        style.toString()
    }.getOrElse { styleJson }

    // ---- tile server ------------------------------------------------------------------------------------------
    /** Plain client for the Range requests to S3 (no interceptor, so no loop). */
    private val s3 = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val readers = ConcurrentHashMap<String, PmTilesReader>()

    private fun reader(release: String) = readers.getOrPut(release) {
        val url = archiveUrl(release)
        PmTilesReader { offset, length ->
            val req = Request.Builder().url(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
            s3.newCall(req).execute().use { r ->
                // 206 only: a 200 would be the whole 180 GB archive.
                if (r.code != 206) throw IOException("HTTP ${r.code} from Overture (release $release)")
                r.body?.bytes()?.takeIf { it.size == length } ?: throw IOException("short read from Overture")
            }
        }
    }

    private val pbf = "application/x-protobuf".toMediaType()

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.url.host != HOST) return@Interceptor chain.proceed(request)
        val seg = request.url.pathSegments // <release>/<z>/<x>/<y>.pbf
        val release = seg.getOrNull(0)
        val z = seg.getOrNull(1)?.toIntOrNull()
        val x = seg.getOrNull(2)?.toIntOrNull()
        val y = seg.getOrNull(3)?.substringBefore('.')?.toIntOrNull()
        if (release == null || z == null || x == null || y == null) {
            return@Interceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(404).message("bad tile")
                .body(ByteArray(0).toResponseBody(pbf)).build()
        }
        val tile = try {
            reader(release).tile(z, x, y)
        } catch (e: Exception) {
            report(e.message ?: e.javaClass.simpleName)
            throw if (e is IOException) e else IOException(e)
        }
        served.incrementAndGet()
        report()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(if (tile == null) 204 else 200).message(if (tile == null) "No Content" else "OK")
            .header("Cache-Control", "max-age=2592000")
            .body((tile ?: ByteArray(0)).toResponseBody(pbf))
            .build()
    }

    /**
     * Puts [interceptor] into MapLibre's HTTP client. Call once, before the first map is created.
     *
     * MapLibre must be initialised first: MapLibre's HTTP class reads MapLibre.getApplicationContext() when it is
     * first loaded, and 1.1.3 crashed at start-up because install() ran before that. Never throws: without the
     * interceptor the map still works, only the Overture buildings are missing (shown in Settings).
     */
    /** True once [install] put the interceptor in place; otherwise the map uses the style without Overture. */
    @Volatile var installed = false
        private set

    fun install(context: Context) {
        try {
            org.maplibre.android.MapLibre.getInstance(context.applicationContext)
            val client = OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(client)
            installed = true
        } catch (t: Throwable) {
            _status.value = "Overture buildings: off — could not set up the tile reader (${t.javaClass.simpleName}: ${t.message})"
        }
    }
}
