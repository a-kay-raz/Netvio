package com.netvio.data.sync

import com.netvio.data.local.PlaybackHistoryEntity
import com.netvio.data.local.WatchlistEntity
import com.netvio.data.profile.ProfileType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TrackerSyncOverlapTest {

    private fun makeWatchlist(
        mediaId: String = "550",
        isDeleted: Boolean = false,
        clientUpdatedAtMs: Long = 1_000_000L
    ) = WatchlistEntity(
        mediaId = mediaId,
        profileId = "default_user",
        title = "Fight Club",
        posterUrl = null,
        backdropUrl = null,
        mediaType = "MOVIE",
        rating = 8.4,
        year = 1999,
        status = "TO_BE_WATCHED",
        addedTimestamp = 900_000L,
        isDirty = true,
        isDeleted = isDeleted,
        clientUpdatedAtMs = clientUpdatedAtMs
    )

    private fun makePlayback(
        provider: String = "tmdb",
        mediaId: String = "1396",
        season: Int? = 1,
        episode: Int? = 3,
        positionMs: Long = 600_000L,
        isCompleted: Boolean = false,
        isDismissed: Boolean = false,
        isDeleted: Boolean = false,
        lastWatchedTimestamp: Long = 1_000_000L,
        clientUpdatedAtMs: Long = 1_000_000L
    ) = PlaybackHistoryEntity(
        id = PlaybackHistoryEntity.buildId(provider, mediaId, season, episode, "default_user"),
        mediaId = mediaId,
        title = "Breaking Bad",
        episodeTitle = "And the Bag's in the River",
        seasonNumber = season,
        episodeNumber = episode,
        posterUrl = null,
        backdropUrl = null,
        currentPositionMs = positionMs,
        durationMs = 2_800_000L,
        lastWatchedTimestamp = lastWatchedTimestamp,
        isCompleted = isCompleted,
        provider = provider,
        mediaType = if (season != null) "TV_SERIES" else "MOVIE",
        isDismissedFromContinueWatching = isDismissed,
        profileId = "default_user",
        isDirty = true,
        isDeleted = isDeleted,
        clientUpdatedAtMs = clientUpdatedAtMs
    )

    @Test
    fun testWatchlistTombstoneBlocksResurrectionAndRequestsRemoteRemoval() {
        val tombstone = makeWatchlist(isDeleted = true, clientUpdatedAtMs = 1_000_000L)

        // Missing or older remote timestamp must stay deleted and request removal from Trakt/Simkl
        assertEquals(
            TrackerReconciliationPolicy.WatchlistPullAction.SKIP_LOCAL_TOMBSTONE_AND_REMOVE_REMOTE,
            TrackerReconciliationPolicy.decideWatchlistPull(tombstone, null)
        )
        assertEquals(
            TrackerReconciliationPolicy.WatchlistPullAction.SKIP_LOCAL_TOMBSTONE_AND_REMOVE_REMOTE,
            TrackerReconciliationPolicy.decideWatchlistPull(tombstone, 999_000L)
        )
        // Within 5s clock skew guard also stays deleted
        assertEquals(
            TrackerReconciliationPolicy.WatchlistPullAction.SKIP_LOCAL_TOMBSTONE_AND_REMOVE_REMOTE,
            TrackerReconciliationPolicy.decideWatchlistPull(tombstone, 1_004_999L)
        )

        // Strictly newer remote re-add (> +5s) restores item
        assertEquals(
            TrackerReconciliationPolicy.WatchlistPullAction.RESURRECT_REMOTE_NEWER,
            TrackerReconciliationPolicy.decideWatchlistPull(tombstone, 1_005_001L)
        )
    }

    @Test
    fun testPlaybackDeletionAcrossAnyProviderBlocksTraktAndSimklResurrection() {
        val deletedFromProvider = makePlayback(
            provider = "cloudstream_addon",
            isDeleted = true,
            lastWatchedTimestamp = 1_000_000L,
            clientUpdatedAtMs = 1_200_000L
        )

        assertEquals(
            TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED,
            TrackerReconciliationPolicy.decidePlaybackPull(
                localRowsForMedia = listOf(deletedFromProvider),
                remoteEventTsMs = 1_100_000L,
                remoteIsCompleted = false,
                isSyntheticPlaceholder = false
            )
        )

        assertEquals(
            TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED,
            TrackerReconciliationPolicy.decidePlaybackPull(
                localRowsForMedia = listOf(deletedFromProvider),
                remoteEventTsMs = 1_100_000L,
                remoteIsCompleted = true,
                isSyntheticPlaceholder = false
            )
        )

        // Strictly newer rewatch after local deletion is allowed to apply
        assertEquals(
            TrackerReconciliationPolicy.PlaybackPullAction.APPLY_REMOTE,
            TrackerReconciliationPolicy.decidePlaybackPull(
                localRowsForMedia = listOf(deletedFromProvider),
                remoteEventTsMs = 1_250_000L,
                remoteIsCompleted = false,
                isSyntheticPlaceholder = false
            )
        )
    }

    @Test
    fun testDismissedContinueWatchingItemRespectsPauseTimestamp() {
        val dismissedRow = makePlayback(
            isDismissed = true,
            lastWatchedTimestamp = 1_000_000L,
            clientUpdatedAtMs = 1_500_000L
        )

        assertEquals(
            TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED,
            TrackerReconciliationPolicy.decidePlaybackPull(
                localRowsForMedia = listOf(dismissedRow),
                remoteEventTsMs = 1_500_000L,
                remoteIsCompleted = false,
                isSyntheticPlaceholder = false
            )
        )

        assertEquals(
            TrackerReconciliationPolicy.PlaybackPullAction.APPLY_REMOTE,
            TrackerReconciliationPolicy.decidePlaybackPull(
                localRowsForMedia = listOf(dismissedRow),
                remoteEventTsMs = 1_500_001L,
                remoteIsCompleted = false,
                isSyntheticPlaceholder = false
            )
        )
    }

    @Test
    fun testSimklSyntheticPlaceholderNeverOverridesExistingOrDeletedProgress() {
        val existingEp3 = makePlayback(season = 1, episode = 3, isDeleted = false)
        assertEquals(
            TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED,
            TrackerReconciliationPolicy.decidePlaybackPull(
                localRowsForMedia = listOf(existingEp3),
                remoteEventTsMs = 9_999_999L,
                remoteIsCompleted = false,
                isSyntheticPlaceholder = true
            )
        )

        val deletedEp = makePlayback(season = 1, episode = 1, isDeleted = true)
        assertEquals(
            TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED,
            TrackerReconciliationPolicy.decidePlaybackPull(
                localRowsForMedia = listOf(deletedEp),
                remoteEventTsMs = 9_999_999L,
                remoteIsCompleted = false,
                isSyntheticPlaceholder = true
            )
        )

        // Fresh device with zero local rows for that title DOES accept Simkl watching placeholder
        assertEquals(
            TrackerReconciliationPolicy.PlaybackPullAction.APPLY_REMOTE,
            TrackerReconciliationPolicy.decidePlaybackPull(
                localRowsForMedia = emptyList(),
                remoteEventTsMs = 1_000_000L,
                remoteIsCompleted = false,
                isSyntheticPlaceholder = true
            )
        )
    }

    @Test
    fun testCompletedEpisodeBlocksStalePauseAndIncompleteLocalUpgradesOnRemoteCompleted() {
        val completedLocal = makePlayback(isCompleted = true, lastWatchedTimestamp = 1_000_000L)
        assertEquals(
            TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_NEWER_OR_COMPLETED,
            TrackerReconciliationPolicy.decidePlaybackPull(
                localRowsForMedia = listOf(completedLocal),
                remoteEventTsMs = 2_000_000L,
                remoteIsCompleted = false,
                isSyntheticPlaceholder = false
            )
        )

        val partialLocal = makePlayback(isCompleted = false, lastWatchedTimestamp = 5_000_000L)
        assertEquals(
            TrackerReconciliationPolicy.PlaybackPullAction.APPLY_REMOTE,
            TrackerReconciliationPolicy.decidePlaybackPull(
                localRowsForMedia = listOf(partialLocal),
                remoteEventTsMs = 2_000_000L,
                remoteIsCompleted = true,
                isSyntheticPlaceholder = false
            )
        )
    }

    @Test
    fun testTrackerProfileIsolationExcludesKidsAndGuestProfiles() {
        assertTrue(TrackerReconciliationPolicy.shouldSyncTrackersForProfile(ProfileType.ADMIN))
        assertTrue(TrackerReconciliationPolicy.shouldSyncTrackersForProfile(ProfileType.STANDARD))
        assertFalse(TrackerReconciliationPolicy.shouldSyncTrackersForProfile(ProfileType.KIDS))
        assertFalse(TrackerReconciliationPolicy.shouldSyncTrackersForProfile(ProfileType.GUEST))
        assertTrue(TrackerReconciliationPolicy.isTvMediaType("TV_SERIES"))
        assertTrue(TrackerReconciliationPolicy.isTvMediaType("ANIME"))
        assertFalse(TrackerReconciliationPolicy.isTvMediaType("MOVIE"))
    }

    @Test
    fun testSyncCoordinatorDataMutexSerializesConcurrentAccess() = runBlocking {
        val inCriticalSection = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        (1..8).map {
            async {
                SyncCoordinator.dataMutex.withLock {
                    val active = inCriticalSection.incrementAndGet()
                    maxConcurrent.updateAndGet { prev -> maxOf(prev, active) }
                    delay(15)
                    inCriticalSection.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maxConcurrent.get())
    }
}
