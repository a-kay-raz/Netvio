package com.netvio.data.cache

import android.content.Context
import coil3.SingletonImageLoader
import com.netvio.core.network.DohDns
import com.netvio.core.p2p.P2pCacheSize
import com.netvio.core.p2p.P2pSettingsRepository
import com.netvio.core.p2p.P2pStreamingEngine
import com.netvio.data.preferences.PreferencesRepository
import com.netvio.download.DownloadEngine
import com.netvio.ui.player.StreamingPlaybackCacheManager
import com.netvio.ui.player.sequential.SequentialStreamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

private const val MB = 1024L * 1024L
private const val DAY_MS = 24L * 60L * 60L * 1000L
private const val AUTO_MAINTENANCE_INTERVAL_MS = 12L * 60L * 60L * 1000L
private const val LOW_STORAGE_FREE_THRESHOLD_BYTES = 512L * MB
private const val LOW_STORAGE_BUDGET_CAP_BYTES = 150L * MB

enum class CacheCategory {
    IMAGES,
    VIDEO_BUFFERS,
    P2P_TORRENT,
    METADATA_AND_TEMP
}

data class CacheBreakdown(
    val imagesBytes: Long = 0L,
    val videoBuffersBytes: Long = 0L,
    val p2pBytes: Long = 0L,
    val metadataAndTempBytes: Long = 0L
) {
    val totalBytes: Long
        get() = imagesBytes + videoBuffersBytes + p2pBytes + metadataAndTempBytes

    fun bytesFor(category: CacheCategory): Long = when (category) {
        CacheCategory.IMAGES -> imagesBytes
        CacheCategory.VIDEO_BUFFERS -> videoBuffersBytes
        CacheCategory.P2P_TORRENT -> p2pBytes
        CacheCategory.METADATA_AND_TEMP -> metadataAndTempBytes
    }
}

data class SubsystemCacheCaps(
    val imagesCapBytes: Long,
    val videoCapBytes: Long,
    val httpCapBytes: Long,
    val p2pCapSize: P2pCacheSize
)

data class CacheCleanupReport(
    val reclaimedBytes: Long,
    val remainingBytes: Long,
    val deletedFilesCount: Int,
    val lowStorageTriggered: Boolean
)

object SmartCachePolicy {

    fun computeSubsystemCaps(
        maxCacheLimitMb: Int,
        p2pEnabled: Boolean = true
    ): SubsystemCacheCaps {
        val clampedMb = maxCacheLimitMb.coerceIn(250, 2048)
        val totalBytes = clampedMb.toLong() * MB
        val imagesCapBytes = (totalBytes * 0.40).toLong().coerceIn(80L * MB, 350L * MB)
        val videoCapBytes = (totalBytes * 0.30).toLong().coerceIn(64L * MB, 350L * MB)
        val httpCapBytes = if (clampedMb <= 250) 25L * MB else 50L * MB
        val p2pCapSize = when {
            !p2pEnabled -> P2pCacheSize.NONE
            clampedMb <= 250 -> P2pCacheSize.NONE
            clampedMb <= 500 -> P2pCacheSize.MB_512
            clampedMb <= 1024 -> P2pCacheSize.GB_1
            else -> P2pCacheSize.GB_2
        }
        return SubsystemCacheCaps(
            imagesCapBytes = imagesCapBytes,
            videoCapBytes = videoCapBytes,
            httpCapBytes = httpCapBytes,
            p2pCapSize = p2pCapSize
        )
    }

    fun effectiveBudgetBytes(
        maxCacheLimitMb: Int,
        freeDiskBytes: Long
    ): Pair<Long, Boolean> {
        val clampedMb = maxCacheLimitMb.coerceIn(250, 2048)
        val normalBudget = clampedMb.toLong() * MB
        return if (freeDiskBytes in 1L until LOW_STORAGE_FREE_THRESHOLD_BYTES) {
            minOf(normalBudget / 2L, LOW_STORAGE_BUDGET_CAP_BYTES) to true
        } else {
            normalBudget to false
        }
    }
}

/**
 * Central Smart Cache Manager for Euthopiar.
 *
 * Critical safety invariants:
 * 1. NEVER directly delete individual files or directories inside live DiskLruCache stores
 *    (`image_cache/` owned by Coil 3 and `http_cache/` owned by OkHttp). Doing so corrupts their
 *    paired `.0`/`.1` entries & `journal`, breaking disk cache reads/writes for the entire session.
 *    Always let Coil & OkHttp enforce their own native LRU `maxSizeBytes`, and only evict them
 *    through their official APIs (`diskCache.clear()` / `DohDns.evictHttpCache()`).
 * 2. ONLY prune files inside explicitly allowlisted unmanaged app cache subdirectories (`updates/`,
 *    `seq_stream_*`, `nuvio-engine/`, `torrent_meta/`, and closed `stream_cache/`). NEVER touch
 *    Android/ART/Room system artifacts in `cacheDir` (such as `euthopiar_database.lck`, `oat_primary/`,
 *    `profileinstaller/`, or WebView caches).
 * 3. NEVER touch offline user downloads (`DownloadEngine.getDownloadDirectory()`).
 */
object AppCacheManager {

    private const val PREFS_NAME = "smart_cache_prefs"
    private const val KEY_COIL_CAP_BYTES = "coil_cap_bytes"
    private const val KEY_LAST_CHECK_MS = "last_auto_check_ms"
    private val maintenanceMutex = Mutex()

    private const val DIR_IMAGE_CACHE = "image_cache"
    private const val DIR_STREAM_CACHE = "stream_cache"
    private const val DIR_SEQ_PREFIX = "seq_stream_"
    private const val DIR_P2P_ENGINE = "nuvio-engine"
    private const val DIR_P2P_META = "torrent_meta"
    private const val DIR_HTTP_CACHE = "http_cache"
    private const val DIR_UPDATES = "updates"

    fun getCoilDiskCacheCapBytes(context: Context): Long {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultCap = SmartCachePolicy.computeSubsystemCaps(500).imagesCapBytes
        val stored = prefs.getLong(KEY_COIL_CAP_BYTES, defaultCap)
        return stored.coerceIn(80L * MB, 350L * MB)
    }

    fun saveCoilDiskCacheCapBytes(context: Context, bytes: Long) {
        val clamped = bytes.coerceIn(80L * MB, 350L * MB)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_COIL_CAP_BYTES, clamped)
            .apply()
    }

    fun applySubsystemCaps(
        context: Context,
        maxCacheLimitMb: Int,
        p2pEnabled: Boolean
    ): SubsystemCacheCaps {
        val caps = SmartCachePolicy.computeSubsystemCaps(maxCacheLimitMb, p2pEnabled)
        saveCoilDiskCacheCapBytes(context, caps.imagesCapBytes)
        try {
            StreamingPlaybackCacheManager.configureMaxSizeBytes(caps.videoCapBytes)
        } catch (_: Throwable) {}
        try {
            P2pSettingsRepository.ensureLoaded()
            P2pSettingsRepository.setCacheSize(caps.p2pCapSize)
        } catch (_: Throwable) {}
        return caps
    }

    private fun resolveExcludedPaths(context: Context): Set<String> {
        val downloadPath = try {
            DownloadEngine(context).getDownloadDirectory().canonicalPath
        } catch (_: Exception) {
            ""
        }
        return if (downloadPath.isNotBlank()) setOf(downloadPath) else emptySet()
    }

    fun inspectBreakdown(context: Context): CacheBreakdown {
        val appCtx = context.applicationContext
        val cacheRoot = appCtx.cacheDir ?: return CacheBreakdown()
        val extRoots = try {
            appCtx.externalCacheDirs?.filterNotNull().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        return inspectDirectoriesBreakdown(
            cacheRoot = cacheRoot,
            externalCacheRoots = extRoots,
            excludedCanonicalPaths = resolveExcludedPaths(appCtx)
        )
    }

    internal fun inspectDirectoriesBreakdown(
        cacheRoot: File,
        externalCacheRoots: List<File>,
        excludedCanonicalPaths: Set<String>
    ): CacheBreakdown {
        val visited = mutableSetOf<String>()

        val imagesBytes = getDirSizeFast(File(cacheRoot, DIR_IMAGE_CACHE), visited, excludedCanonicalPaths)

        var videoBytes = getDirSizeFast(File(cacheRoot, DIR_STREAM_CACHE), visited, excludedCanonicalPaths)
        cacheRoot.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name.startsWith(DIR_SEQ_PREFIX)) {
                videoBytes += getDirSizeFast(child, visited, excludedCanonicalPaths)
            }
        }

        val p2pBytes = getDirSizeFast(File(cacheRoot, DIR_P2P_ENGINE), visited, excludedCanonicalPaths) +
            getDirSizeFast(File(cacheRoot, DIR_P2P_META), visited, excludedCanonicalPaths)

        // Only measure our managed HTTP cache & update APKs — never count ART oat_primary or Room .lck files
        var metadataBytes = 0L
        metadataBytes += getDirSizeFast(File(cacheRoot, DIR_HTTP_CACHE), visited, excludedCanonicalPaths)
        metadataBytes += getDirSizeFast(File(cacheRoot, DIR_UPDATES), visited, excludedCanonicalPaths)
        for (extRoot in externalCacheRoots) {
            metadataBytes += getDirSizeFast(File(extRoot, DIR_UPDATES), visited, excludedCanonicalPaths)
        }

        return CacheBreakdown(
            imagesBytes = imagesBytes,
            videoBuffersBytes = videoBytes,
            p2pBytes = p2pBytes,
            metadataAndTempBytes = metadataBytes
        )
    }

    suspend fun clearCategory(
        context: Context,
        category: CacheCategory
    ): Long = withContext(Dispatchers.IO) {
        maintenanceMutex.withLock {
            val appCtx = context.applicationContext
            val before = inspectBreakdown(appCtx).bytesFor(category)
            val excludes = resolveExcludedPaths(appCtx)
            val cacheRoot = appCtx.cacheDir

            when (category) {
                CacheCategory.IMAGES -> {
                    // Use Coil's official DiskCache & MemoryCache clear APIs so the live
                    // DiskLruCache journal stays intact and future poster caching keeps working!
                    try {
                        val imageLoader = SingletonImageLoader.get(appCtx)
                        imageLoader.diskCache?.clear()
                        imageLoader.memoryCache?.clear()
                    } catch (_: Throwable) {}
                }
                CacheCategory.VIDEO_BUFFERS -> {
                    try {
                        StreamingPlaybackCacheManager.release()
                    } catch (_: Throwable) {}
                    try {
                        SequentialStreamManager.cleanOrphanedCache(appCtx)
                    } catch (_: Throwable) {}
                    if (cacheRoot != null) {
                        deleteDirSafely(File(cacheRoot, DIR_STREAM_CACHE), mutableSetOf(), excludes, isRoot = false)
                    }
                }
                CacheCategory.P2P_TORRENT -> {
                    try {
                        P2pStreamingEngine.clearCacheIfIdle(appCtx)
                    } catch (_: Throwable) {}
                }
                CacheCategory.METADATA_AND_TEMP -> {
                    // Evict OkHttp cache through its official API so the live DiskLruCache stays valid!
                    try {
                        DohDns.evictHttpCache()
                    } catch (_: Throwable) {}
                    if (cacheRoot != null) {
                        deleteDirSafely(File(cacheRoot, DIR_UPDATES), mutableSetOf(), excludes, isRoot = false)
                    }
                    try {
                        appCtx.externalCacheDirs?.filterNotNull()?.forEach { extDir ->
                            deleteDirSafely(File(extDir, DIR_UPDATES), mutableSetOf(), excludes, isRoot = false)
                        }
                    } catch (_: Exception) {}
                }
            }

            val after = inspectBreakdown(appCtx).bytesFor(category)
            (before - after).coerceAtLeast(0L)
        }
    }

    suspend fun clearAllCache(context: Context): Long = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val before = inspectBreakdown(appCtx).totalBytes
        maintenanceMutex.withLock {
            val excludes = resolveExcludedPaths(appCtx)
            val cacheRoot = appCtx.cacheDir

            // 1. Video playback buffers (release SimpleCache first, then remove stream_cache & orphaned seq dirs)
            try {
                StreamingPlaybackCacheManager.release()
            } catch (_: Throwable) {}
            try {
                SequentialStreamManager.cleanOrphanedCache(appCtx)
            } catch (_: Throwable) {}
            if (cacheRoot != null) {
                deleteDirSafely(File(cacheRoot, DIR_STREAM_CACHE), mutableSetOf(), excludes, isRoot = false)
            }

            // 2. Managed HTTP metadata cache (official OkHttp evictAll — never delete folder out from under client!)
            try {
                DohDns.evictHttpCache()
            } catch (_: Throwable) {}

            // 3. Managed Coil image cache (official Coil clear — never delete folder out from under ImageLoader!)
            try {
                val imageLoader = SingletonImageLoader.get(appCtx)
                imageLoader.diskCache?.clear()
                imageLoader.memoryCache?.clear()
            } catch (_: Throwable) {}

            // 4. P2P torrent cache (only when idle)
            try {
                P2pStreamingEngine.clearCacheIfIdle(appCtx)
            } catch (_: Throwable) {}

            // 5. Stale OTA update APKs
            if (cacheRoot != null) {
                deleteDirSafely(File(cacheRoot, DIR_UPDATES), mutableSetOf(), excludes, isRoot = false)
            }
            try {
                appCtx.externalCacheDirs?.filterNotNull()?.forEach { extDir ->
                    deleteDirSafely(File(extDir, DIR_UPDATES), mutableSetOf(), excludes, isRoot = false)
                }
            } catch (_: Exception) {}
        }
        val after = inspectBreakdown(appCtx).totalBytes
        (before - after).coerceAtLeast(0L)
    }

    suspend fun cleanP2pCacheIfIdle(context: Context): Long = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val before = inspectBreakdown(appCtx).p2pBytes
        if (before <= 0L) return@withContext 0L
        val cleared = P2pStreamingEngine.clearCacheIfIdle(appCtx)
        if (!cleared) return@withContext 0L
        val after = inspectBreakdown(appCtx).p2pBytes
        (before - after).coerceAtLeast(0L)
    }

    suspend fun runAutomaticMaintenance(
        context: Context,
        force: Boolean = false
    ): CacheCleanupReport = withContext(Dispatchers.IO) {
        maintenanceMutex.withLock {
            val appCtx = context.applicationContext
            val nowMs = System.currentTimeMillis()
            val fastPrefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastFastCheckMs = fastPrefs.getLong(KEY_LAST_CHECK_MS, 0L)
            val cacheRoot = appCtx.cacheDir ?: return@withLock CacheCleanupReport(0L, 0L, 0, false)
            val freeDiskBytes = try { cacheRoot.usableSpace } catch (_: Exception) { 0L }
            val isLowDisk = freeDiskBytes in 1L until LOW_STORAGE_FREE_THRESHOLD_BYTES

            // Fast-path skip on routine launches so we don't even hit DataStore or walk cache dirs
            if (!force && !isLowDisk && lastFastCheckMs > 0L && (nowMs - lastFastCheckMs) < AUTO_MAINTENANCE_INTERVAL_MS) {
                val excludes = resolveExcludedPaths(appCtx)
                val activeSeqDir = SequentialStreamManager.activeSessionDirName()
                val hygiene = runSafeTempHygieneOnly(cacheRoot, excludes, nowMs, activeSeqDir)
                return@withLock hygiene
            }

            val prefsRepo = PreferencesRepository.getInstance(appCtx)
            val autoEnabled = prefsRepo.autoCacheCleanupEnabled.first()
            val maxLimitMb = prefsRepo.maxCacheLimitMb.first()
            val retentionDays = prefsRepo.cacheRetentionDays.first()
            val p2pEnabled = prefsRepo.p2pStreamsEnabled.first()
            val lastCleanupMs = prefsRepo.lastCacheCleanupTimestamp.first()

            applySubsystemCaps(appCtx, maxLimitMb, p2pEnabled)

            val extRoots = try {
                appCtx.externalCacheDirs?.filterNotNull().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            val excludes = resolveExcludedPaths(appCtx)
            val (budgetBytes, lowStorage) = SmartCachePolicy.effectiveBudgetBytes(maxLimitMb, freeDiskBytes)

            val activeSeqDir = SequentialStreamManager.activeSessionDirName()
            val isP2pActive = P2pStreamingEngine.isStreamActive()
            val isStreamCacheOpen = StreamingPlaybackCacheManager.isCacheOpen()

            fastPrefs.edit().putLong(KEY_LAST_CHECK_MS, nowMs).apply()

            if (!autoEnabled && !force) {
                val tempReport = runSafeTempHygieneOnly(
                    cacheRoot = cacheRoot,
                    excludedCanonicalPaths = excludes,
                    nowMs = nowMs,
                    activeSeqSessionDirName = activeSeqDir
                )
                return@withLock tempReport.copy(lowStorageTriggered = lowStorage)
            }

            val currentBreakdown = inspectDirectoriesBreakdown(cacheRoot, extRoots, excludes)
            val intervalElapsed = (nowMs - lastCleanupMs) >= AUTO_MAINTENANCE_INTERVAL_MS
            val overBudget = currentBreakdown.totalBytes > budgetBytes
            if (!force && !intervalElapsed && !overBudget && !lowStorage) {
                val hygieneReport = runSafeTempHygieneOnly(
                    cacheRoot = cacheRoot,
                    excludedCanonicalPaths = excludes,
                    nowMs = nowMs,
                    activeSeqSessionDirName = activeSeqDir
                )
                return@withLock hygieneReport.copy(
                    remainingBytes = (currentBreakdown.totalBytes - hygieneReport.reclaimedBytes).coerceAtLeast(0L),
                    lowStorageTriggered = lowStorage
                )
            }

            val report = runMaintenanceInDirectories(
                cacheRoot = cacheRoot,
                externalCacheRoots = extRoots,
                excludedCanonicalPaths = excludes,
                maxCacheLimitMb = maxLimitMb,
                retentionDays = retentionDays,
                freeDiskBytes = freeDiskBytes,
                nowMs = nowMs,
                activeSeqSessionDirName = activeSeqDir,
                isP2pActive = isP2pActive,
                isStreamCacheOpen = isStreamCacheOpen,
                onEvictHttpCache = if (lowStorage) { { DohDns.evictHttpCache() } } else null
            )

            try {
                prefsRepo.recordCacheCleanupResult(nowMs, report.reclaimedBytes)
            } catch (_: Throwable) {}

            report
        }
    }

    private fun runSafeTempHygieneOnly(
        cacheRoot: File,
        excludedCanonicalPaths: Set<String>,
        nowMs: Long,
        activeSeqSessionDirName: String?
    ): CacheCleanupReport {
        var reclaimed = 0L
        var deletedCount = 0
        val staleApkCutoff = nowMs - DAY_MS

        val updatesDir = File(cacheRoot, DIR_UPDATES)
        if (updatesDir.exists() && updatesDir.isDirectory) {
            updatesDir.listFiles()?.forEach { file ->
                if (file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                    !isExcludedCanonical(file, excludedCanonicalPaths) &&
                    file.lastModified() < staleApkCutoff
                ) {
                    val len = file.length()
                    if (file.delete()) {
                        reclaimed += len
                        deletedCount++
                    }
                }
            }
        }

        cacheRoot.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name.startsWith(DIR_SEQ_PREFIX) && child.name != activeSeqSessionDirName) {
                val sz = getDirSizeFast(child, mutableSetOf(), excludedCanonicalPaths)
                if (deleteDirSafely(child, mutableSetOf(), excludedCanonicalPaths, isRoot = false)) {
                    reclaimed += sz
                    deletedCount++
                }
            }
        }

        return CacheCleanupReport(
            reclaimedBytes = reclaimed,
            remainingBytes = 0L,
            deletedFilesCount = deletedCount,
            lowStorageTriggered = false
        )
    }

    internal fun runMaintenanceInDirectories(
        cacheRoot: File,
        externalCacheRoots: List<File>,
        excludedCanonicalPaths: Set<String>,
        maxCacheLimitMb: Int,
        retentionDays: Int,
        freeDiskBytes: Long,
        nowMs: Long,
        activeSeqSessionDirName: String? = null,
        isP2pActive: Boolean = false,
        isStreamCacheOpen: Boolean = false,
        onEvictHttpCache: (() -> Unit)? = null
    ): CacheCleanupReport {
        val beforeTotal = inspectDirectoriesBreakdown(cacheRoot, externalCacheRoots, excludedCanonicalPaths).totalBytes
        var reclaimed = 0L
        var deletedCount = 0

        // Pass 1: Safe temp hygiene (stale APKs > 24h & orphaned seq_stream_ dirs)
        val hygiene = runSafeTempHygieneOnly(cacheRoot, excludedCanonicalPaths, nowMs, activeSeqSessionDirName)
        reclaimed += hygiene.reclaimedBytes
        deletedCount += hygiene.deletedFilesCount

        // Pass 2: Age / TTL pruning STRICTLY inside unmanaged app directories.
        // NEVER touch files directly inside `image_cache/` (Coil DiskLruCache) or `http_cache/` (OkHttp DiskLruCache)
        // — both enforce their own strict native LRU caps and paired `.0`/`.1` journal integrity.
        val genericCutoffMs = nowMs - retentionDays.coerceIn(1, 90).toLong() * DAY_MS

        if (!isP2pActive) {
            val (r1, c1) = pruneFilesOlderThan(
                dir = File(cacheRoot, DIR_P2P_ENGINE),
                cutoffMs = genericCutoffMs,
                excludedCanonicalPaths = excludedCanonicalPaths,
                skipFileNames = emptySet()
            )
            val (r2, c2) = pruneFilesOlderThan(
                dir = File(cacheRoot, DIR_P2P_META),
                cutoffMs = genericCutoffMs,
                excludedCanonicalPaths = excludedCanonicalPaths,
                skipFileNames = emptySet()
            )
            reclaimed += r1 + r2
            deletedCount += c1 + c2
        }

        if (!isStreamCacheOpen) {
            val (rv, cv) = pruneFilesOlderThan(
                dir = File(cacheRoot, DIR_STREAM_CACHE),
                cutoffMs = genericCutoffMs,
                excludedCanonicalPaths = excludedCanonicalPaths,
                skipFileNames = setOf("cached_content_index.exi")
            )
            reclaimed += rv
            deletedCount += cv
        }

        // Pass 3: Priority LRU budget enforcement ONLY across unmanaged app directories
        // (Updates -> Idle P2P -> Closed Video Buffers). Managed DiskLruCaches self-bound cleanly.
        val (budgetBytes, lowStorage) = SmartCachePolicy.effectiveBudgetBytes(maxCacheLimitMb, freeDiskBytes)
        var currentTotal = (beforeTotal - reclaimed).coerceAtLeast(0L)

        if (currentTotal > budgetBytes) {
            val priorityBuckets = buildList<List<File>> {
                add(listOf(File(cacheRoot, DIR_UPDATES)))
                if (!isP2pActive) {
                    add(listOf(File(cacheRoot, DIR_P2P_ENGINE), File(cacheRoot, DIR_P2P_META)))
                }
                if (!isStreamCacheOpen) {
                    add(listOf(File(cacheRoot, DIR_STREAM_CACHE)))
                }
            }

            for (bucketRoots in priorityBuckets) {
                if (currentTotal <= budgetBytes) break
                val candidates = collectDeletableFiles(
                    roots = bucketRoots,
                    excludedCanonicalPaths = excludedCanonicalPaths,
                    skipFileNames = setOf("cached_content_index.exi")
                ).sortedBy { it.lastModified() }

                for (file in candidates) {
                    if (currentTotal <= budgetBytes) break
                    val len = file.length()
                    if (file.delete()) {
                        reclaimed += len
                        currentTotal = (currentTotal - len).coerceAtLeast(0L)
                        deletedCount++
                    }
                }
            }

            // Only under genuine low-storage emergency, safely evict HTTP cache via OkHttp API
            if (currentTotal > budgetBytes && lowStorage && onEvictHttpCache != null) {
                try {
                    onEvictHttpCache()
                } catch (_: Throwable) {}
            }
        }

        // Only prune empty subdirs inside our own unmanaged directories
        pruneEmptyChildDirs(File(cacheRoot, DIR_UPDATES), excludedCanonicalPaths)
        if (!isP2pActive) {
            pruneEmptyChildDirs(File(cacheRoot, DIR_P2P_ENGINE), excludedCanonicalPaths)
            pruneEmptyChildDirs(File(cacheRoot, DIR_P2P_META), excludedCanonicalPaths)
        }
        if (!isStreamCacheOpen) {
            pruneEmptyChildDirs(File(cacheRoot, DIR_STREAM_CACHE), excludedCanonicalPaths)
        }

        val remainingTotal = inspectDirectoriesBreakdown(cacheRoot, externalCacheRoots, excludedCanonicalPaths).totalBytes
        return CacheCleanupReport(
            reclaimedBytes = maxOf(reclaimed, (beforeTotal - remainingTotal).coerceAtLeast(0L)),
            remainingBytes = remainingTotal,
            deletedFilesCount = deletedCount,
            lowStorageTriggered = lowStorage
        )
    }

    private fun pruneFilesOlderThan(
        dir: File,
        cutoffMs: Long,
        excludedCanonicalPaths: Set<String>,
        skipFileNames: Set<String>
    ): Pair<Long, Int> {
        if (!dir.exists() || isExcludedCanonical(dir, excludedCanonicalPaths)) return 0L to 0
        var reclaimed = 0L
        var deletedCount = 0
        val files = collectDeletableFiles(listOf(dir), excludedCanonicalPaths, skipFileNames)
        for (file in files) {
            val modified = file.lastModified()
            if (modified in 1L until cutoffMs) {
                val len = file.length()
                if (file.delete()) {
                    reclaimed += len
                    deletedCount++
                }
            }
        }
        return reclaimed to deletedCount
    }

    private fun collectDeletableFiles(
        roots: List<File>,
        excludedCanonicalPaths: Set<String>,
        skipFileNames: Set<String>
    ): List<File> {
        val result = mutableListOf<File>()
        val visitedDirs = mutableSetOf<String>()
        fun walk(node: File) {
            try {
                if (!node.exists() || Files.isSymbolicLink(node.toPath())) return
                if (node.isDirectory) {
                    val canonicalPath = node.canonicalPath
                    if (excludedCanonicalPaths.any { canonicalPath.startsWith(it) }) return
                    if (!visitedDirs.add(canonicalPath)) return
                    node.listFiles()?.forEach(::walk)
                } else if (node.isFile && node.name !in skipFileNames) {
                    if (excludedCanonicalPaths.isEmpty() || !isExcludedCanonical(node, excludedCanonicalPaths)) {
                        result.add(node)
                    }
                }
            } catch (_: Exception) {}
        }
        roots.forEach(::walk)
        return result
    }

    private fun pruneEmptyChildDirs(root: File, excludedCanonicalPaths: Set<String>) {
        if (!root.exists() || !root.isDirectory || isExcludedCanonical(root, excludedCanonicalPaths)) return
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && !Files.isSymbolicLink(child.toPath())) {
                pruneEmptyChildDirs(child, excludedCanonicalPaths)
                if (!isExcludedCanonical(child, excludedCanonicalPaths) && child.listFiles()?.isEmpty() == true) {
                    child.delete()
                }
            }
        }
    }

    private fun isExcludedCanonical(file: File, excludedCanonicalPaths: Set<String>): Boolean {
        if (excludedCanonicalPaths.isEmpty()) return false
        return try {
            val canonical = file.canonicalPath
            excludedCanonicalPaths.any { canonical.startsWith(it) }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fast, symlink-safe directory sizing. Validates canonical boundary on directories once
     * rather than running `realpath()` on thousands of individual cached poster files.
     */
    private fun getDirSizeFast(
        dir: File?,
        visitedCanonical: MutableSet<String>,
        excludeCanonical: Set<String>
    ): Long {
        if (dir == null || !dir.exists()) return 0L
        if (try { Files.isSymbolicLink(dir.toPath()) } catch (_: Exception) { true }) return 0L
        val canonical = try { dir.canonicalFile } catch (_: Exception) { return 0L }
        val canonicalPath = canonical.absolutePath
        if (!visitedCanonical.add(canonicalPath)) return 0L
        if (excludeCanonical.any { canonicalPath.startsWith(it) }) return 0L

        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (file in files) {
            try {
                if (file.isDirectory) {
                    size += getDirSizeFast(file, visitedCanonical, excludeCanonical)
                } else if (file.isFile) {
                    size += file.length()
                }
            } catch (_: Exception) {}
        }
        return size
    }

    private fun deleteDirSafely(
        dir: File?,
        visitedCanonical: MutableSet<String>,
        excludeCanonical: Set<String>,
        isRoot: Boolean
    ): Boolean {
        if (dir == null || !dir.exists()) return true
        val canonical = try { dir.canonicalFile } catch (_: Exception) { return true }
        val canonicalPath = canonical.absolutePath
        if (!visitedCanonical.add(canonicalPath)) return true
        if (excludeCanonical.any { canonicalPath.startsWith(it) }) return true

        var allDeleted = true
        val files = dir.listFiles() ?: return true
        for (file in files) {
            try {
                if (Files.isSymbolicLink(file.toPath())) {
                    file.delete()
                    continue
                }
                if (file.isDirectory) {
                    val fileCanonicalPath = file.canonicalFile.absolutePath
                    if (!fileCanonicalPath.startsWith(canonicalPath)) continue
                    if (excludeCanonical.any { fileCanonicalPath.startsWith(it) }) continue
                    val childDeleted = deleteDirSafely(file, visitedCanonical, excludeCanonical, isRoot = false)
                    allDeleted = allDeleted && childDeleted
                } else {
                    val deleted = file.delete()
                    if (!deleted) file.deleteOnExit()
                    allDeleted = allDeleted && deleted
                }
            } catch (_: Exception) {
                allDeleted = false
            }
        }
        if (!isRoot && dir.exists()) {
            val dirDeleted = dir.delete()
            if (!dirDeleted) dir.deleteOnExit()
            allDeleted = allDeleted && dirDeleted
        }
        return allDeleted
    }
}
