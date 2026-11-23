package com.netvio.data.cache

import com.netvio.core.p2p.P2pCacheSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppCacheManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val mb = 1024L * 1024L
    private val dayMs = 24L * 60L * 60L * 1000L

    @Test
    fun computeSubsystemCaps_scalesProportionallyWithTotalLimitAndP2pToggle() {
        val low = SmartCachePolicy.computeSubsystemCaps(maxCacheLimitMb = 250, p2pEnabled = true)
        assertEquals(100L * mb, low.imagesCapBytes)
        assertEquals(75L * mb, low.videoCapBytes)
        assertEquals(25L * mb, low.httpCapBytes)
        assertEquals(P2pCacheSize.NONE, low.p2pCapSize)

        val balanced = SmartCachePolicy.computeSubsystemCaps(maxCacheLimitMb = 500, p2pEnabled = true)
        assertEquals(200L * mb, balanced.imagesCapBytes)
        assertEquals(150L * mb, balanced.videoCapBytes)
        assertEquals(50L * mb, balanced.httpCapBytes)
        assertEquals(P2pCacheSize.MB_512, balanced.p2pCapSize)

        val high = SmartCachePolicy.computeSubsystemCaps(maxCacheLimitMb = 2048, p2pEnabled = true)
        assertEquals(350L * mb, high.imagesCapBytes)
        assertEquals(350L * mb, high.videoCapBytes)
        assertEquals(50L * mb, high.httpCapBytes)
        assertEquals(P2pCacheSize.GB_2, high.p2pCapSize)

        val highNoP2p = SmartCachePolicy.computeSubsystemCaps(maxCacheLimitMb = 2048, p2pEnabled = false)
        assertEquals(P2pCacheSize.NONE, highNoP2p.p2pCapSize)
    }

    @Test
    fun effectiveBudgetBytes_shrinksUnderLowDeviceStorageGuardrail() {
        val (normalBudget, lowTriggeredNormal) = SmartCachePolicy.effectiveBudgetBytes(
            maxCacheLimitMb = 1024,
            freeDiskBytes = 4L * 1024L * mb
        )
        assertEquals(1024L * mb, normalBudget)
        assertFalse(lowTriggeredNormal)

        val (tightBudget, lowTriggeredTight) = SmartCachePolicy.effectiveBudgetBytes(
            maxCacheLimitMb = 1024,
            freeDiskBytes = 200L * mb
        )
        assertEquals(150L * mb, tightBudget)
        assertTrue(lowTriggeredTight)
    }

    @Test
    fun runMaintenanceInDirectories_cleansUnmanagedCachesAndNeverCorruptsDiskLruCachesOrSystemFiles() {
        val cacheRoot = tempFolder.newFolder("app_cache")
        val downloadDir = tempFolder.newFolder("offline_downloads")
        val nowMs = 1_750_000_000_000L

        // 1. Stale & fresh OTA APKs
        val updatesDir = File(cacheRoot, "updates").apply { mkdirs() }
        val staleApk = createSizedFile(updatesDir, "old.apk", 2048, nowMs - 3 * dayMs)
        val freshApk = createSizedFile(updatesDir, "new.apk", 2048, nowMs - 2 * 3600_000L)

        // 2. Active vs orphaned sequential stream directories
        val activeSeqDir = File(cacheRoot, "seq_stream_active_1").apply { mkdirs() }
        val activeChunk = createSizedFile(activeSeqDir, "chunk_0.bin", 1024, nowMs - 5 * dayMs)
        val orphanSeqDir = File(cacheRoot, "seq_stream_orphan_99").apply { mkdirs() }
        createSizedFile(orphanSeqDir, "chunk_0.bin", 4096, nowMs - 5 * dayMs)

        // 3. Stale P2P files (> 7d) vs fresh P2P files
        val p2pDir = File(cacheRoot, "nuvio-engine").apply { mkdirs() }
        val oldP2pFile = createSizedFile(p2pDir, "piece_old.dat", 8192, nowMs - 9 * dayMs)
        val freshP2pFile = createSizedFile(p2pDir, "piece_fresh.dat", 4096, nowMs - 1 * dayMs)

        // 4. Managed DiskLruCache stores (Coil image_cache & OkHttp http_cache) — MUST NEVER be mutated directly
        val imageDir = File(cacheRoot, "image_cache").apply { mkdirs() }
        val coilJournal = createSizedFile(imageDir, "journal", 512, nowMs - 30 * dayMs)
        val oldPosterHeader = createSizedFile(imageDir, "abc123.0", 256, nowMs - 30 * dayMs)
        val oldPosterBody = createSizedFile(imageDir, "abc123.1", 4096, nowMs - 30 * dayMs)

        val httpDir = File(cacheRoot, "http_cache").apply { mkdirs() }
        val httpJournal = createSizedFile(httpDir, "journal", 512, nowMs - 30 * dayMs)
        val oldMetaHeader = createSizedFile(httpDir, "def456.0", 256, nowMs - 30 * dayMs)
        val oldMetaBody = createSizedFile(httpDir, "def456.1", 2048, nowMs - 30 * dayMs)

        // 5. Android / Room system files inside cacheDir — MUST NEVER be touched or counted
        val roomLock = createSizedFile(cacheRoot, "euthopiar_database.lck", 64, nowMs - 40 * dayMs)
        val oatDir = File(cacheRoot, "oat_primary").apply { mkdirs() }
        val oatFile = createSizedFile(oatDir, "base.odex", 8192, nowMs - 40 * dayMs)

        // 6. Protected user offline downloads
        val downloadedMovie = createSizedFile(downloadDir, "movie.mp4", 16384, nowMs - 30 * dayMs)

        val report = AppCacheManager.runMaintenanceInDirectories(
            cacheRoot = cacheRoot,
            externalCacheRoots = emptyList(),
            excludedCanonicalPaths = setOf(downloadDir.canonicalPath),
            maxCacheLimitMb = 500,
            retentionDays = 7,
            freeDiskBytes = 10L * 1024L * mb,
            nowMs = nowMs,
            activeSeqSessionDirName = "seq_stream_active_1",
            isP2pActive = false,
            isStreamCacheOpen = false
        )

        // Unmanaged stale files cleaned
        assertFalse(staleApk.exists())
        assertTrue(freshApk.exists())
        assertFalse(orphanSeqDir.exists())
        assertTrue(activeChunk.exists())
        assertFalse(oldP2pFile.exists())
        assertTrue(freshP2pFile.exists())

        // Managed DiskLruCache paired files & journals untouched (zero corruption!)
        assertTrue(coilJournal.exists())
        assertTrue(oldPosterHeader.exists())
        assertTrue(oldPosterBody.exists())
        assertTrue(httpJournal.exists())
        assertTrue(oldMetaHeader.exists())
        assertTrue(oldMetaBody.exists())

        // Room lock, ART oat_primary, and user downloads untouched
        assertTrue(roomLock.exists())
        assertTrue(oatFile.exists())
        assertTrue(downloadedMovie.exists())

        // Breakdown must not count Room lock or oat_primary
        val breakdown = AppCacheManager.inspectDirectoriesBreakdown(
            cacheRoot = cacheRoot,
            externalCacheRoots = emptyList(),
            excludedCanonicalPaths = setOf(downloadDir.canonicalPath)
        )
        assertEquals(2048L + 512L + 256L + 2048L, breakdown.metadataAndTempBytes)
        assertTrue(report.reclaimedBytes >= 2048L + 4096L + 8192L)
        assertFalse(report.lowStorageTriggered)
    }

    @Test
    fun runMaintenanceInDirectories_respectsActiveP2pAndOpenStreamCacheLocks() {
        val cacheRoot = tempFolder.newFolder("app_cache_active_locks")
        val nowMs = 1_750_000_000_000L

        val p2pDir = File(cacheRoot, "nuvio-engine").apply { mkdirs() }
        val activeTorrentChunk = createSizedFile(p2pDir, "active_piece.dat", 8192, nowMs - 20 * dayMs)

        val streamDir = File(cacheRoot, "stream_cache").apply { mkdirs() }
        val activeVideoChunk = createSizedFile(streamDir, "chunk_active.exo", 8192, nowMs - 20 * dayMs)

        AppCacheManager.runMaintenanceInDirectories(
            cacheRoot = cacheRoot,
            externalCacheRoots = emptyList(),
            excludedCanonicalPaths = emptySet(),
            maxCacheLimitMb = 250,
            retentionDays = 1,
            freeDiskBytes = 100L * mb,
            nowMs = nowMs,
            activeSeqSessionDirName = null,
            isP2pActive = true,
            isStreamCacheOpen = true
        )

        assertTrue("Active P2P cache must never be deleted mid-stream", activeTorrentChunk.exists())
        assertTrue("Open ExoPlayer SimpleCache must never be deleted mid-playback", activeVideoChunk.exists())
    }

    private fun createSizedFile(parent: File, name: String, bytes: Int, lastModifiedMs: Long): File {
        return File(parent, name).apply {
            writeBytes(ByteArray(bytes) { 1 })
            setLastModified(lastModifiedMs)
        }
    }
}
