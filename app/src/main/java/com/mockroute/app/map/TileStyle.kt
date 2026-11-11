package com.mockroute.app.map

/** Map styles. Never use tile.openstreetmap.org (see CLAUDE.md). */
object TileStyle {
    /** OpenFreeMap "liberty" vector style (free, no key). */
    const val PRIMARY_URL = "https://tiles.openfreemap.org/styles/liberty"

    /** Used if the primary style fails to load. Low detail, but keeps the app usable. */
    const val FALLBACK_URL = "https://tiles.openfreemap.org/styles/bright"

    /** Last resort if OpenFreeMap itself is unreachable. */
    const val LAST_RESORT_URL = "https://demotiles.maplibre.org/style.json"

    val chain = listOf(PRIMARY_URL, FALLBACK_URL, LAST_RESORT_URL)

    /** Dark OpenFreeMap style: the default look (matches the app's dark theme; our markers stand out on it). */
    const val DARK_URL = "https://tiles.openfreemap.org/styles/dark"

    /** Style URLs to try in order for the chosen map style ("dark" or "light"). */
    fun chainFor(mapStyle: String): List<String> = if (mapStyle == "light") chain else listOf(DARK_URL) + chain

    const val ATTRIBUTION = "© OpenStreetMap contributors · OpenFreeMap · OpenMapTiles · Overture Maps"
}
