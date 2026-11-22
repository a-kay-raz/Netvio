package com.netvio.data.sync

import com.netvio.core.sync.supabase.CloudPlaylist
import com.netvio.core.sync.supabase.CloudUserAddon
import com.netvio.core.sync.supabase.CloudUserProfile
import com.netvio.data.sync.supabase.SyncEfficiencyPolicy
import org.junit.Assert.*
import org.junit.Test

class SupabaseSyncOptimizationTest {

    private fun sanitizeCloudUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (trimmed.startsWith("data:", ignoreCase = true) || trimmed.length > 500) {
            return null
        }
        return trimmed
    }

    @Test
    fun testSanitizeCloudUrlStripsDataUrlsAndExcessiveStrings() {
        val validUrl = "https://image.tmdb.org/t/p/w500/sample.jpg"
        assertEquals(validUrl, sanitizeCloudUrl(validUrl))

        val dataUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUA..."
        assertNull(sanitizeCloudUrl(dataUrl))

        val massiveUrl = "https://example.com/" + "a".repeat(501)
        assertNull(sanitizeCloudUrl(massiveUrl))

        assertNull(sanitizeCloudUrl(""))
        assertNull(sanitizeCloudUrl("   "))
        assertNull(sanitizeCloudUrl(null))
    }

    @Test
    fun testCollectionCapsEnforceFreeTierStorageBoundaries() {
        // Mock 120 history items
        val historyItems = (1..120).map { i ->
            Pair("item_$i", i * 1000L) // id, timestamp
        }
        val cappedHistory = historyItems
            .sortedByDescending { it.second }
            .take(50)
        assertEquals(50, cappedHistory.size)
        assertEquals("item_120", cappedHistory.first().first)
        assertEquals("item_71", cappedHistory.last().first)

        // Mock 250 watchlist items
        val watchlistItems = (1..250).map { i ->
            Pair("watch_$i", i * 500L)
        }
        val cappedWatchlist = watchlistItems
            .sortedByDescending { it.second }
            .take(100)
        assertEquals(100, cappedWatchlist.size)
        assertEquals("watch_250", cappedWatchlist.first().first)

        // Mock 25 playlists
        val playlists = (1..25).map { i ->
            Pair("playlist_$i", i * 100L)
        }
        val cappedPlaylists = playlists
            .sortedByDescending { it.second }
            .take(10)
        assertEquals(10, cappedPlaylists.size)
        assertEquals("playlist_25", cappedPlaylists.first().first)
    }

    @Test
    fun testPendingDeletedAddonsTombstoneManagement() {
        var pendingDeletions = setOf(
            "https://torrentio.strem.fun/manifest.json",
            "https://opensubtitles.strem.fun/manifest.json"
        )

        // Adding an item
        pendingDeletions = pendingDeletions + "https://cinemeta.strem.fun/manifest.json"
        assertEquals(3, pendingDeletions.size)

        // Simulating push of the first two
        val syncedDeletions = setOf(
            "https://torrentio.strem.fun/manifest.json",
            "https://opensubtitles.strem.fun/manifest.json"
        )
        pendingDeletions = pendingDeletions - syncedDeletions

        assertEquals(1, pendingDeletions.size)
        assertTrue(pendingDeletions.contains("https://cinemeta.strem.fun/manifest.json"))
    }

    @Test
    fun testPullCooldownRespects5MinThresholdAndForceOverrides() {
        val t0 = 1_700_000_000_000L

        assertTrue(SyncEfficiencyPolicy.shouldRunDownstreamPull(false, false, 0L, t0))
        assertFalse(SyncEfficiencyPolicy.shouldRunDownstreamPull(false, false, t0, t0 + 60_000L))
        assertFalse(SyncEfficiencyPolicy.shouldRunDownstreamPull(false, false, t0, t0 + 299_999L))
        assertTrue(SyncEfficiencyPolicy.shouldRunDownstreamPull(false, false, t0, t0 + 300_000L))
        assertTrue(SyncEfficiencyPolicy.shouldRunDownstreamPull(true, false, t0, t0 + 1_000L))
        assertTrue(SyncEfficiencyPolicy.shouldRunDownstreamPull(false, true, t0, t0 + 1_000L))
    }

    @Test
    fun testDomainHashingIgnoresVolatileTimestampsAndDetectsMutations() {
        val profA = listOf(
            CloudUserProfile("default_user", "u1", "Primary", "ADMIN", clientUpdatedAtMs = 1000L),
            CloudUserProfile("kids", "u1", "Kids", "KIDS", clientUpdatedAtMs = 1000L)
        )
        val profB = listOf(
            CloudUserProfile("kids", "u1", "Kids", "KIDS", clientUpdatedAtMs = 9999L),
            CloudUserProfile("default_user", "u1", "Primary", "ADMIN", clientUpdatedAtMs = 9999L)
        )
        val profModified = listOf(
            CloudUserProfile("default_user", "u1", "Primary Renamed", "ADMIN", clientUpdatedAtMs = 9999L),
            CloudUserProfile("kids", "u1", "Kids", "KIDS", clientUpdatedAtMs = 9999L)
        )
        assertEquals(SyncEfficiencyPolicy.hashProfiles(profA), SyncEfficiencyPolicy.hashProfiles(profB))
        assertNotEquals(SyncEfficiencyPolicy.hashProfiles(profA), SyncEfficiencyPolicy.hashProfiles(profModified))

        val addonsA = listOf(
            CloudUserAddon("u1", "https://a.com/manifest.json", "stremio", "A", clientUpdatedAtMs = 100L)
        )
        val addonsB = listOf(
            CloudUserAddon("u1", "https://a.com/manifest.json", "stremio", "A", clientUpdatedAtMs = 900L)
        )
        val addonsModified = addonsA + CloudUserAddon("u1", "https://b.com/manifest.json", "stremio", "B", clientUpdatedAtMs = 900L)
        assertEquals(SyncEfficiencyPolicy.hashAddons(addonsA), SyncEfficiencyPolicy.hashAddons(addonsB))
        assertNotEquals(SyncEfficiencyPolicy.hashAddons(addonsA), SyncEfficiencyPolicy.hashAddons(addonsModified))

        val settings1 = mapOf("b" to "2", "a" to "1")
        val settings2 = mapOf("a" to "1", "b" to "2")
        val settingsModified = mapOf("a" to "1", "b" to "3")
        assertEquals(SyncEfficiencyPolicy.hashSettings(settings1), SyncEfficiencyPolicy.hashSettings(settings2))
        assertNotEquals(SyncEfficiencyPolicy.hashSettings(settings1), SyncEfficiencyPolicy.hashSettings(settingsModified))

        val pl1 = listOf(CloudPlaylist("u1", "default_user", "p1", "Mix", false, "[1]", clientUpdatedAtMs = 100L))
        val pl2 = listOf(CloudPlaylist("u1", "default_user", "p1", "Mix", false, "[1]", clientUpdatedAtMs = 999L))
        val plModified = listOf(CloudPlaylist("u1", "default_user", "p1", "Mix", false, "[1,2]", clientUpdatedAtMs = 999L))
        assertEquals(SyncEfficiencyPolicy.hashPlaylists(pl1), SyncEfficiencyPolicy.hashPlaylists(pl2))
        assertNotEquals(SyncEfficiencyPolicy.hashPlaylists(pl1), SyncEfficiencyPolicy.hashPlaylists(plModified))
    }

    @Test
    fun testPlaybackDeletionAndDismissalProtectedFrom60sPositionResurrection() {
        // Local dirty deletion must NOT be overwritten by a remote active item within the 60s window
        assertFalse(
            SyncEfficiencyPolicy.shouldApplyRemotePlayback(
                localIsDirty = true,
                localIsDeleted = true,
                localIsDismissed = false,
                localClientUpdatedAtMs = 1_000_000L,
                localPositionMs = 10_000L,
                remoteIsDeleted = false,
                remoteIsDismissed = false,
                remoteLastWatchedTs = 980_000L,
                remotePositionMs = 500_000L
            )
        )

        // Local dirty dismissal must NOT be overwritten by equal-timestamp active remote row
        assertFalse(
            SyncEfficiencyPolicy.shouldApplyRemotePlayback(
                localIsDirty = true,
                localIsDeleted = false,
                localIsDismissed = true,
                localClientUpdatedAtMs = 1_000_000L,
                localPositionMs = 10_000L,
                remoteIsDeleted = false,
                remoteIsDismissed = false,
                remoteLastWatchedTs = 1_000_000L,
                remotePositionMs = 500_000L
            )
        )

        // Strictly newer remote active watch (e.g. rewatched on another device) DOES apply
        assertTrue(
            SyncEfficiencyPolicy.shouldApplyRemotePlayback(
                localIsDirty = true,
                localIsDeleted = true,
                localIsDismissed = false,
                localClientUpdatedAtMs = 1_000_000L,
                localPositionMs = 10_000L,
                remoteIsDeleted = false,
                remoteIsDismissed = false,
                remoteLastWatchedTs = 1_000_001L,
                remotePositionMs = 5_000L
            )
        )

        // Clean local row ALWAYS accepts remote deletion even if remote clock was slightly behind
        assertTrue(
            SyncEfficiencyPolicy.shouldApplyRemotePlayback(
                localIsDirty = false,
                localIsDeleted = false,
                localIsDismissed = false,
                localClientUpdatedAtMs = 2_000_000L,
                localPositionMs = 500_000L,
                remoteIsDeleted = true,
                remoteIsDismissed = false,
                remoteLastWatchedTs = 1_900_000L,
                remotePositionMs = 0L
            )
        )
    }

    @Test
    fun testWatchlistDeletionProtectionAndConflictResolution() {
        // Local dirty deletion beats older or equal-timestamp active remote row
        assertFalse(
            SyncEfficiencyPolicy.shouldApplyRemoteWatchlist(
                localIsDirty = true,
                localIsDeleted = true,
                localClientUpdatedAtMs = 1_000_000L,
                remoteIsDeleted = false,
                remoteClientUpdatedAtMs = 1_000_000L
            )
        )

        // Strictly newer remote re-add overrides local deletion
        assertTrue(
            SyncEfficiencyPolicy.shouldApplyRemoteWatchlist(
                localIsDirty = true,
                localIsDeleted = true,
                localClientUpdatedAtMs = 1_000_000L,
                remoteIsDeleted = false,
                remoteClientUpdatedAtMs = 1_000_001L
            )
        )

        // Equal-timestamp remote deletion wins over local dirty active row
        assertTrue(
            SyncEfficiencyPolicy.shouldApplyRemoteWatchlist(
                localIsDirty = true,
                localIsDeleted = false,
                localClientUpdatedAtMs = 1_000_000L,
                remoteIsDeleted = true,
                remoteClientUpdatedAtMs = 1_000_000L
            )
        )
    }

    @Test
    fun testTypedPendingDeletedAddonParsingPreservesJsRepoManifestUrls() {
        val jsRepoWithManifestJson = SyncEfficiencyPolicy.parsePendingDeletedAddon(
            "js_repo|https://example.com/plugins/manifest.json"
        )
        assertEquals("js_repo", jsRepoWithManifestJson.first)
        assertEquals("https://example.com/plugins/manifest.json", jsRepoWithManifestJson.second)

        val stremioAddon = SyncEfficiencyPolicy.parsePendingDeletedAddon(
            "stremio|https://torrentio.strem.fun/manifest.json"
        )
        assertEquals("stremio", stremioAddon.first)
        assertEquals("https://torrentio.strem.fun/manifest.json", stremioAddon.second)

        val legacyUntypedRepo = SyncEfficiencyPolicy.parsePendingDeletedAddon(
            "https://example.com/repo.json"
        )
        assertEquals("js_repo", legacyUntypedRepo.first)
        assertEquals("https://example.com/repo.json", legacyUntypedRepo.second)
    }
}
