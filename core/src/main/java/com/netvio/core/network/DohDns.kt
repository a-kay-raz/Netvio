package com.netvio.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cache
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * High-performance DNS-over-HTTPS (DoH) resolver for OkHttp.
 *
 * Automatically bypasses ISP DNS poisoning, censorship, and DNS resolution failures
 * by querying Cloudflare (1.1.1.1) and Google (8.8.8.8) DoH endpoints over TLS.
 *
 * Implements:
 * - Zero-reflection JSON parsing (R8-safe & instant cold init)
 * - Single-flight in-flight lookup coalescing (eliminates cold-start DNS thundering herds)
 * - In-memory TTL caching (15 min) with stale-entry fallback
 * - Parallel host prefetching and graceful fallback to system DNS
 */
class DohDns(
    private val timeoutSeconds: Long = 3
) : Dns {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Isolated bootstrap client connecting directly by raw IP with non-throttled dispatcher
    private val bootstrapClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 12 })
        .connectionPool(ConnectionPool(maxIdleConnections = 6, keepAliveDuration = 5, timeUnit = TimeUnit.MINUTES))
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<List<InetAddress>>>()

    private val ipv4Pattern = Pattern.compile("""^(\d{1,3}\.){3}\d{1,3}$""")

    override fun lookup(hostname: String): List<InetAddress> {
        val trimmed = hostname.trim()

        // 0. Loopback / localhost bypass
        if (trimmed.equals("localhost", ignoreCase = true) || trimmed == "127.0.0.1" || trimmed == "::1" || trimmed == "[::1]") {
            val host = trimmed.removeSurrounding("[", "]")
            return listOf(InetAddress.getByName(host))
        }

        // 1. Literal IP addresses need no DNS lookup
        if (ipv4Pattern.matcher(trimmed).matches()) {
            return listOf(InetAddress.getByName(trimmed))
        }

        // 2. DoH bootstrap endpoints
        if (trimmed == "1.1.1.1" || trimmed == "cloudflare-dns.com") {
            return listOf(InetAddress.getByAddress(trimmed, byteArrayOf(1, 1, 1, 1)))
        }
        if (trimmed == "1.0.0.1") {
            return listOf(InetAddress.getByAddress(trimmed, byteArrayOf(1, 0, 0, 1)))
        }
        if (trimmed == "8.8.8.8" || trimmed == "dns.google") {
            return listOf(InetAddress.getByAddress(trimmed, byteArrayOf(8, 8, 8, 8)))
        }
        if (trimmed == "8.8.4.4") {
            return listOf(InetAddress.getByAddress(trimmed, byteArrayOf(8, 8, 4, 4)))
        }

        // 3. In-memory TTL Cache
        val cached = cache[trimmed]
        val now = System.currentTimeMillis()
        if (cached != null && cached.expiresAtMs > now) {
            return cached.addresses
        }

        // 4. Single-flight coalescing: if multiple threads request the same host simultaneously,
        // only ONE performs the DoH race while all others await the shared future.
        val createdFuture = CompletableFuture<List<InetAddress>>()
        val existingFuture = inFlight.putIfAbsent(trimmed, createdFuture)
        if (existingFuture != null) {
            val shared = try {
                existingFuture.get(timeoutSeconds + 2, TimeUnit.SECONDS)
            } catch (_: Exception) {
                emptyList()
            }
            if (shared.isNotEmpty()) return shared
            if (cached != null && cached.addresses.isNotEmpty()) return cached.addresses
        }

        val addresses = try {
            // Concurrently race DoH endpoints: Cloudflare (1.1.1.1), Google (8.8.8.8), and Cloudflare Secondary (1.0.0.1)
            var resolved = raceDoh(trimmed, timeoutSeconds)

            // Fallback: System DNS
            if (resolved.isEmpty()) {
                try {
                    resolved = Dns.SYSTEM.lookup(trimmed)
                } catch (e: Exception) {
                    if (cached != null && cached.addresses.isNotEmpty()) {
                        resolved = cached.addresses
                    } else {
                        if (existingFuture == null) {
                            createdFuture.complete(emptyList())
                            inFlight.remove(trimmed, createdFuture)
                        }
                        throw UnknownHostException("Unable to resolve host '$trimmed' via DoH or System DNS: ${e.message}")
                    }
                }
            }

            if (resolved.isNotEmpty()) {
                cache[trimmed] = CacheEntry(
                    addresses = resolved,
                    expiresAtMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15)
                )
            }
            if (existingFuture == null) {
                createdFuture.complete(resolved)
                inFlight.remove(trimmed, createdFuture)
            }
            resolved
        } catch (e: UnknownHostException) {
            throw e
        } catch (t: Throwable) {
            if (existingFuture == null) {
                createdFuture.complete(emptyList())
                inFlight.remove(trimmed, createdFuture)
            }
            if (cached != null && cached.addresses.isNotEmpty()) {
                cached.addresses
            } else {
                throw UnknownHostException("Unable to resolve host '$trimmed': ${t.message}")
            }
        }

        return addresses
    }

    private fun raceDoh(hostname: String, timeoutSec: Long): List<InetAddress> {
        val calls = ConcurrentLinkedQueue<Call>()
        val tasks = listOf<Callable<List<InetAddress>>>(
            Callable { queryCloudflare(hostname, calls) },
            Callable { queryGoogle(hostname, calls) },
            Callable { queryCloudflareSecondary(hostname, calls) }
        )
        val ecs = ExecutorCompletionService<List<InetAddress>>(dohExecutor)
        val futures = tasks.map { task -> ecs.submit(task) }
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSec)

        var completedCount = 0
        while (completedCount < tasks.size) {
            val remainingMs = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
            if (remainingMs <= 0) break
            try {
                val future = ecs.poll(remainingMs, TimeUnit.MILLISECONDS) ?: break
                completedCount++
                val result = try { future.get() } catch (_: Exception) { emptyList() }
                if (result.isNotEmpty()) {
                    calls.forEach { runCatching { it.cancel() } }
                    futures.forEach { it.cancel(true) }
                    return result
                }
            } catch (_: Exception) {
                break
            }
        }
        calls.forEach { runCatching { it.cancel() } }
        futures.forEach { it.cancel(true) }
        return emptyList()
    }

    private fun queryCloudflare(hostname: String, calls: ConcurrentLinkedQueue<Call>): List<InetAddress> {
        val url = "https://1.1.1.1/dns-query?name=$hostname&type=A"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()
        return executeDohRequest(request, hostname, calls)
    }

    private fun queryCloudflareSecondary(hostname: String, calls: ConcurrentLinkedQueue<Call>): List<InetAddress> {
        val url = "https://1.0.0.1/dns-query?name=$hostname&type=A"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()
        return executeDohRequest(request, hostname, calls)
    }

    private fun queryGoogle(hostname: String, calls: ConcurrentLinkedQueue<Call>): List<InetAddress> {
        val url = "https://8.8.8.8/resolve?name=$hostname&type=A"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        return executeDohRequest(request, hostname, calls)
    }

    private fun executeDohRequest(
        request: Request,
        hostname: String,
        calls: ConcurrentLinkedQueue<Call>
    ): List<InetAddress> {
        val call = bootstrapClient.newCall(request)
        calls.add(call)
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val bodyStr = response.body?.string() ?: return emptyList()
                parseDnsJson(bodyStr, hostname)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseDnsJson(jsonStr: String, hostname: String): List<InetAddress> {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val status = root["Status"]?.jsonPrimitive?.intOrNull ?: -1
            if (status != 0) return emptyList()

            val answerNode = root["Answer"]?.jsonArray ?: return emptyList()

            val list = mutableListOf<InetAddress>()
            for (elem in answerNode) {
                val obj = elem.jsonObject
                val type = obj["type"]?.jsonPrimitive?.intOrNull ?: continue
                // type 1 is DNS Type A (IPv4)
                if (type == 1) {
                    val ipStr = obj["data"]?.jsonPrimitive?.content ?: continue
                    if (ipv4Pattern.matcher(ipStr).matches()) {
                        try {
                            val raw = InetAddress.getByName(ipStr).address
                            list.add(InetAddress.getByAddress(hostname, raw))
                        } catch (_: Exception) {}
                    }
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Prefetches DNS records concurrently in the background so cold network hops have zero DNS delay.
     */
    fun prefetch(vararg hosts: String) {
        for (h in hosts) {
            dohExecutor.execute {
                try {
                    lookup(h)
                } catch (_: Throwable) {}
            }
        }
    }

    companion object {
        private val dohExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "doh-dns-worker").apply { isDaemon = true }
        }

        val DEFAULT = DohDns()

        val defaultPool = ConnectionPool(maxIdleConnections = 16, keepAliveDuration = 5, timeUnit = TimeUnit.MINUTES)
        val defaultDispatcher = Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 32 }

        /** Root shared client. All derived clients share this connection pool, dispatcher, and SSL session cache. */
        val sharedBaseClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dns(DEFAULT)
                .eventListener(BandwidthEstimator.Default.eventListener)
                .connectionPool(defaultPool)
                .dispatcher(defaultDispatcher)
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

        @Volatile
        private var cachedClient: OkHttpClient? = null

        /** Optional disk-cache initializer for the shared client */
        fun initHttpCache(cacheDirectory: File, maxSizeBytes: Long = 50L * 1024 * 1024) {
            if (cachedClient != null) return
            synchronized(this) {
                if (cachedClient == null) {
                    try {
                        val cacheDir = File(cacheDirectory, "http_cache")
                        if (!cacheDir.exists()) cacheDir.mkdirs()
                        val httpCache = Cache(cacheDir, maxSizeBytes)
                        cachedClient = sharedBaseClient.newBuilder()
                            .cache(httpCache)
                            .addNetworkInterceptor(ForceCacheInterceptor)
                            .build()
                    } catch (_: Throwable) {}
                }
            }
        }

        /** Evicts all cached HTTP metadata responses from the shared disk cache */
        fun evictHttpCache() {
            try {
                cachedClient?.cache?.evictAll()
            } catch (_: Throwable) {}
        }

        /** Client optimized for metadata (TMDB, AniList, Cinemeta) with disk cache if initialized */
        val metadataClient: OkHttpClient
            get() = cachedClient ?: sharedBaseClient

        /**
         * Creates an OkHttpClient configured with automatic DNS-over-HTTPS.
         * Keeps clean isolation for scrapers and streaming CDNs.
         */
        fun createOkHttpClient(
            connectTimeoutSeconds: Long = 30,
            readTimeoutSeconds: Long = 45,
            dns: Dns = DEFAULT
        ): OkHttpClient {
            return OkHttpClient.Builder()
                .dns(dns)
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}

private object ForceCacheInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val resp = chain.proceed(req)
        val host = req.url.host
        val isCacheableMetadataHost = host == "api.themoviedb.org" ||
            host == "euthopiar-vault.sehgalvansh716.workers.dev" ||
            host == "v3-cinemeta.strem.io"
        if (req.method != "GET" || !isCacheableMetadataHost || !resp.isSuccessful) return resp
        // Strip upstream CloudFront/CDN `Age`, `Expires`, and `Pragma` headers!
        // Upstream CloudFront often sends `Age: 15000+` which causes OkHttp's RFC 7234 freshness check
        // to mark newly-cached responses as immediately expired (100% cache miss on every page).
        return resp.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Age")
            .removeHeader("Expires")
            .header("Cache-Control", "public, max-age=1800, stale-while-revalidate=86400")
            .build()
    }
}
