package com.mockroute.app.map

import android.content.Context
import com.mockroute.core.tiles.MvtSlim
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
        val latest = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                s3.newCall(Request.Builder().url(CATALOG).build()).execute().use { r ->
                    if (r.isSuccessful) JSONObject(r.body?.string() ?: "").optString("latest").takeIf { it.matches(Regex("[0-9][0-9.-]+")) } else null
                }
            }.getOrNull()
        } ?: return null
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
    /** True once [install] put the interceptor in place; otherwise the map uses the style without Overture. */
    @Volatile var installed = false
        private set

    /** Plain client for the Range requests to S3 (no interceptor, so no loop). */
    private val s3 = OkHttpClient.Builder()
        .dns(com.mockroute.app.net.BlockSafeDns) // the user's phone DNS answers 0.0.0.0 for the S3 host
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

    /** Only what the style uses; everything else (ids, sources, names…) is cut out of each tile ([MvtSlim]). */
    private val KEEP = setOf("height", "num_floors", "min_height", "is_underground", "has_parts")

    // ---- loading: disk cache + shared in-flight fetches + neighbour prefetch ---------------------------------
    // 1.1.6: buildings stopped at the edge of the first view. Dense z14 tiles are ~1.8 MB; MapLibre fetched them 5
    // at a time and kept waiting for tiles already off screen. Now tiles are slimmed (~5× smaller), cached on disk,
    // fetched by a pool of workers, the 8 neighbours of every requested tile are prefetched (newest first), and
    // a request MapLibre cancels stops waiting at once (its fetch still finishes into the cache).
    @Volatile private var cacheDir: File? = null
    private const val CACHE_LIMIT_BYTES = 300L * 1024 * 1024
    private val inFlight = ConcurrentHashMap<String, java.util.concurrent.Future<ByteArray?>>()
    private val workers = java.util.concurrent.ThreadPoolExecutor(
        6, 6, 30, TimeUnit.SECONDS,
        // LIFO: the tiles around where the camera is NOW go first.
        // Stale ones (the camera has moved on) fall off the end: cancelled, so nobody waits on them.
        object : java.util.concurrent.LinkedBlockingDeque<Runnable>() {
            override fun offer(e: Runnable): Boolean {
                while (size > 48) (pollLast() as? TileTask)?.drop()
                return offerFirst(e)
            }
        },
    ).apply { allowCoreThreadTimeOut(true) }

    private class TileTask(val key: String, body: () -> ByteArray?) : java.util.concurrent.FutureTask<ByteArray?>(body) {
        fun drop() { cancel(false); inFlight.remove(key, this) }
    }

    private fun key(release: String, z: Int, x: Int, y: Int) = "$release/$z-$x-$y"
    private fun cacheFile(key: String) = cacheDir?.let { File(it, key.replace('/', '_') + ".mvt") }

    /** Last ~40 tiles in memory (slimmed tiles are ~0.1–0.5 MB). */
    private val memory = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 40
    }

    private fun readCache(key: String): ByteArray? = synchronized(memory) { memory[key] }
        ?: cacheFile(key)?.takeIf { it.exists() }?.let { f ->
            runCatching { f.setLastModified(System.currentTimeMillis()); f.readBytes() }.getOrNull()
                ?.also { synchronized(memory) { memory[key] = it } }
        }

    private fun writeCache(key: String, data: ByteArray) {
        synchronized(memory) { memory[key] = data }
        val f = cacheFile(key) ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(data)
            tmp.renameTo(f)
        }
        if (served.get() % 50 == 0) trimCache()
    }

    private fun trimCache() = runCatching {
        val files = cacheDir?.listFiles()?.sortedBy { it.lastModified() } ?: return@runCatching
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= CACHE_LIMIT_BYTES) break; total -= f.length(); f.delete() }
    }

    /** Starts (or joins) the fetch of one tile. The result is slimmed and cached; empty tile = empty array. */
    private fun fetch(release: String, z: Int, x: Int, y: Int): java.util.concurrent.Future<ByteArray?> {
        val k = key(release, z, x, y)
        inFlight[k]?.let { return it }
        val task = TileTask(k) {
            try {
                readCache(k) ?: run {
                    val raw = reader(release).tile(z, x, y)
                    val slim = if (raw == null) ByteArray(0) else runCatching { MvtSlim.slim(raw, KEEP) }.getOrDefault(raw)
                    writeCache(k, slim)
                    slim
                }
            } finally {
                inFlight.remove(k)
            }
        }
        val existing = inFlight.putIfAbsent(k, task)
        if (existing != null) return existing
        workers.execute(task)
        return task
    }

    private fun prefetchAround(release: String, z: Int, x: Int, y: Int) {
        if (z < 14) return
        val n = 1 shl z
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = (x + dx + n) % n
            val ny = y + dy
            if (ny !in 0 until n) continue
            val k = key(release, z, nx, ny)
            if (cacheFile(k)?.exists() == true || inFlight.containsKey(k)) continue
            fetch(release, z, nx, ny)
        }
    }

    private fun awaitTile(chain: Interceptor.Chain, release: String, z: Int, x: Int, y: Int): ByteArray {
        readCache(key(release, z, x, y))?.let { return it }
        var future = fetch(release, z, x, y)
        while (true) {
            if (chain.call().isCanceled()) throw IOException("Canceled") // tile left the screen
            try {
                return future.get(200, TimeUnit.MILLISECONDS) ?: ByteArray(0)
            } catch (_: java.util.concurrent.TimeoutException) {
                // keep waiting
            } catch (_: java.util.concurrent.CancellationException) {
                future = fetch(release, z, x, y) // our prefetch queue dropped it: ask again
            } catch (e: java.util.concurrent.ExecutionException) {
                val cause = e.cause ?: e
                report(cause.message ?: cause.javaClass.simpleName)
                throw if (cause is IOException) cause else IOException(cause)
            }
        }
    }

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
        val tile: ByteArray = try {
            awaitTile(chain, release, z, x, y)
        } catch (e: IOException) {
            throw e
        } catch (t: Throwable) {
            // Never let anything but IOException out of an OkHttp interceptor: on MapLibre's threads it would crash.
            report(t.message ?: t.javaClass.simpleName)
            throw IOException(t)
        }
        runCatching { prefetchAround(release, z, x, y) }
        served.incrementAndGet()
        report()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(if (tile.isEmpty()) 204 else 200).message(if (tile.isEmpty()) "No Content" else "OK")
            .header("Cache-Control", "max-age=2592000")
            .body(tile.toResponseBody(pbf))
            .build()
    }

    /**
     * Puts [interceptor] into MapLibre's HTTP client. Call once, before the first map is created.
     *
     * MapLibre must be initialised first: MapLibre's HTTP class reads MapLibre.getApplicationContext() when it is
     * first loaded, and 1.1.3 crashed at start-up because install() ran before that. Never throws: without the
     * interceptor the map still works, only the Overture buildings are missing (shown in Settings).
     */
    fun install(context: Context) {
        try {
            org.maplibre.android.MapLibre.getInstance(context.applicationContext)
            cacheDir = File(context.cacheDir, "overture-tiles").apply { mkdirs() }
            // MapLibre's default allows 5 parallel requests per host; building tiles need more to keep up.
            val dispatcher = okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 }
            val client = OkHttpClient.Builder()
                .dispatcher(dispatcher)
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
