package com.netvio.data.cache

import android.content.Context
import com.netvio.core.model.CatalogRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disk cache for the Home catalog (TMDB rows + Stremio rails as returned by
 * [com.netvio.core.repository.ProviderRepository.getHomeCatalog]).
 *
 * The in-memory catalog cache dies with the app process, so every cold start
 * re-fetched all 17 TMDB feeds from the network before first paint. This
 * file lets Home paint instantly from the last good catalog, then refresh
 * from the network in the background (stale-while-revalidate). Entries older
 * than 24h are ignored. A corrupt or unreadable file is treated as a miss.
 * Stored in cacheDir so the system may evict it under storage pressure.
 */
@Serializable
private data class CachedHomeCatalog(
    val savedAtMs: Long,
    val rows: List<CatalogRow>
)

object HomeCatalogDiskCache {

    private const val FILE_NAME = "home_catalog_cache.json"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24h

    private val json = Json { ignoreUnknownKeys = true }

    fun save(context: Context, rows: List<CatalogRow>) {
        if (rows.isEmpty()) return
        try {
            val payload = CachedHomeCatalog(
                savedAtMs = System.currentTimeMillis(),
                rows = rows
            )
            File(context.cacheDir, FILE_NAME).writeText(
                json.encodeToString(CachedHomeCatalog.serializer(), payload)
            )
        } catch (_: Exception) {
            // Cache write failure must never break the catalog load.
        }
    }

    fun load(context: Context): List<CatalogRow>? {
        return try {
            val file = File(context.cacheDir, FILE_NAME)
            if (!file.exists()) return null
            val payload = json.decodeFromString(
                CachedHomeCatalog.serializer(),
                file.readText()
            )
            if (System.currentTimeMillis() - payload.savedAtMs > MAX_AGE_MS) return null
            payload.rows.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
