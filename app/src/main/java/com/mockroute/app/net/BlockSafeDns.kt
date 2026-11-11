package com.mockroute.app.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * DNS that first asks the phone's resolver and, if that says "blocked" (0.0.0.0 / ::, loopback, or nothing),
 * asks Cloudflare or Google over HTTPS instead (bootstrapped by IP, so the phone's DNS isn't needed for that).
 *
 * Why: on the user's phone the resolver answered 0.0.0.0 for Overture's S3 host (an ad-blocking Private DNS /
 * blocker app / carrier filter), so no Overture building tile could load. Only used by the Overture tile client.
 */
object BlockSafeDns : Dns {
    private val bootstrapClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun ip(s: String) = InetAddress.getByAddress(s, s.split('.').map { it.toInt().toByte() }.toByteArray())

    private val resolvers: List<Dns> by lazy {
        listOf(
            DnsOverHttps.Builder().client(bootstrapClient).url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(ip("1.1.1.1"), ip("1.0.0.1")).includeIPv6(false).build(),
            DnsOverHttps.Builder().client(bootstrapClient).url("https://dns.google/dns-query".toHttpUrl())
                .bootstrapDnsHosts(ip("8.8.8.8"), ip("8.8.4.4")).includeIPv6(false).build(),
        )
    }

    private fun usable(list: List<InetAddress>?) = list?.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }.orEmpty()

    /** Hosts the phone's DNS blocked, remembered so we go straight to DNS-over-HTTPS next time. */
    private val blocked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname !in blocked) {
            val system = usable(runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull())
            if (system.isNotEmpty()) return system
            blocked += hostname
        }
        var last: Throwable? = null
        for (r in resolvers) {
            try {
                val found = usable(r.lookup(hostname))
                if (found.isNotEmpty()) return found
            } catch (t: Throwable) {
                last = t
            }
        }
        throw UnknownHostException(
            "$hostname is blocked by the phone's DNS and DNS-over-HTTPS failed too (${last?.message ?: "no answer"})",
        )
    }
}
