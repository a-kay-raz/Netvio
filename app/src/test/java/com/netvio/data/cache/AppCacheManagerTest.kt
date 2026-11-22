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
    fun runMaintenanceInDirectories_cleansStaleTempsTtlLruPriorityAndProtectsDownloads() {
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

        // 3. Stale P2P & Video files (> 7d), fresh image (< 14d), and protected downloads
        val p2pDir = File(cacheRoot, "nuvio-engine").apply { mkdirs() }
        val oldP2pFile = createSizedFile(p2pDir, "piece_old.dat", 8192, nowMs - 9 * dayMs)
        val freshP2pFile = createSizedFile(p2pDir, "piece_fresh.dat", 4096, nowMs - 1 * dayMs)

        val imageDir = File(cacheRoot, "image_cache").apply { mkdirs() }
        val coilJournal = createSizedFile(imageDir, "journal", 512, nowMs - 30 * dayMs)
        val posterTenDaysOld = createSizedFile(imageDir, "poster_10d.img", 2048, nowMs - 10 * dayMs)

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
            isP2pActive = false
        )

        assertFalse(staleApk.exists())
        assertTrue(freshApk.exists())
        assertFalse(orphanSeqDir.exists())
        assertTrue(activeChunk.exists())
        assertFalse(oldP2pFile.exists())
        assertTrue(freshP2pFile.exists())
        assertTrue(coilJournal.exists())
        // Images get at least a 14-day retention window so 10-day-old posters stay cached
        assertTrue(posterTenDaysOld.exists())
        assertTrue(downloadedMovie.exists())
        assertTrue(report.reclaimedBytes >= 2048L + 4096L + 8192L)
        assertFalse(report.lowStorageTriggered)
    }

    @Test
    fun runMaintenanceInDirectories_respectsActiveP2pStreamLock() {
        val cacheRoot = tempFolder.newFolder("app_cache_active_p2p")
        val nowMs = 1_750_000_000_000L

        val p2pDir = File(cacheRoot, "nuvio-engine").apply { mkdirs() }
        val activeTorrentChunk = createSizedFile(p2pDir, "active_piece.dat", 8192, nowMs - 20 * dayMs)

        AppCacheManager.runMaintenanceInDirectories(
            cacheRoot = cacheRoot,
            externalCacheRoots = emptyList(),
            excludedCanonicalPaths = emptySet(),
            maxCacheLimitMb = 250,
            retentionDays = 1,
            freeDiskBytes = 100L * mb, // low storage triggered
            nowMs = nowMs,
            activeSeqSessionDirName = null,
            isP2pActive = true
        )

        assertTrue("Active P2P cache must never be deleted mid-stream", activeTorrentChunk.exists())
    }

    private fun createSizedFile(parent: File, name: String, bytes: Int, lastModifiedMs: Long): File {
        return File(parent, name).apply {
            writeBytes(ByteArray(bytes) { 1 })
            setLastModified(lastModifiedMs)
        }
    }
}
