package com.mockroute.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal JSON-over-HTTPS POST (no extra dependency). Never logs headers: they may hold API keys. */
object HttpJson {
    data class Response(val code: Int, val body: String?)

    @Throws(IOException::class)
    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): Response =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json, application/geo+json")
                setRequestProperty("User-Agent", "MockRoute-Android")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                Response(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }
}
