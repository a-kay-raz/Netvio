package com.netvio.download

import com.netvio.core.model.EpisodeItem
import com.netvio.core.model.MediaDetail
import com.netvio.core.model.MediaType
import com.netvio.core.streams.StreamItem
import com.netvio.data.local.PlaybackHistoryEntity
import com.netvio.ui.details.downloads.BatchEpisodeDownloadPlanner
import com.netvio.ui.details.downloads.BatchEpisodeFilter
import com.netvio.ui.details.downloads.BatchPreferredQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiEpisodeBatchDownloadTest {

    private fun sampleSeriesDetail(episodes: List<EpisodeItem>): MediaDetail = MediaDetail(
        id = "series_101",
        title = "Sample Show",
        url = "https://example.com/show",
        posterUrl = null,
        backdropUrl = null,
        type = MediaType.TV_SERIES,
        year = 2024,
        synopsis = null,
        episodes = episodes
    )

    private fun episode(season: Int, ep: Int): EpisodeItem = EpisodeItem(
        id = "ep_${season}_$ep",
        title = "Episode $ep",
        seasonNumber = season,
        episodeNumber = ep,
        data = "data_${season}_$ep"
    )

    @Test
    fun testComputeSelectionForUnwatchedAllAndCustomExcludesDownloaded() {
        val episodes = (1..6).map { episode(1, it) } + listOf(episode(2, 1))
        val detail = sampleSeriesDetail(episodes)

        val season1 = BatchEpisodeDownloadPlanner.episodesForSeason(detail.episodes, 1)
        assertEquals(6, season1.size)

        val playbackMap = mapOf(
            (1 to 1) to PlaybackHistoryEntity(
                id = "p1",
                mediaId = detail.id,
                title = detail.title,
                episodeTitle = "Ep 1",
                seasonNumber = 1,
                episodeNumber = 1,
                posterUrl = null,
                backdropUrl = null,
                currentPositionMs = 1000L,
                durationMs = 1000L,
                isCompleted = true
            ),
            (1 to 2) to PlaybackHistoryEntity(
                id = "p2",
                mediaId = detail.id,
                title = detail.title,
                episodeTitle = "Ep 2",
                seasonNumber = 1,
                episodeNumber = 2,
                posterUrl = null,
                backdropUrl = null,
                currentPositionMs = 920L,
                durationMs = 1000L,
                isCompleted = false
            ),
            (1 to 3) to PlaybackHistoryEntity(
                id = "p3",
                mediaId = detail.id,
                title = detail.title,
                episodeTitle = "Ep 3",
                seasonNumber = 1,
                episodeNumber = 3,
                posterUrl = null,
                backdropUrl = null,
                currentPositionMs = 300L,
                durationMs = 1000L,
                isCompleted = false
            )
        )

        val downloads = listOf(
            DownloadItem(
                id = "${detail.id}_s1e4",
                mediaId = detail.id,
                episodeId = "ep_1_4",
                title = detail.title,
                state = DownloadState.COMPLETED,
                seasonNumber = 1,
                episodeNumber = 4
            ),
            DownloadItem(
                id = "${detail.id}_s1e6",
                mediaId = detail.id,
                episodeId = "ep_1_6",
                title = detail.title,
                state = DownloadState.FAILED,
                seasonNumber = 1,
                episodeNumber = 6
            )
        )

        val lookup = BatchEpisodeDownloadPlanner.buildDownloadedLookup(downloads, detail)

        val allSelectable = BatchEpisodeDownloadPlanner.computeSelectionForFilter(
            seasonEpisodes = season1,
            playbackMap = playbackMap,
            downloadedLookup = lookup,
            filter = BatchEpisodeFilter.ALL
        )
        assertEquals(setOf("ep_1_1", "ep_1_2", "ep_1_3", "ep_1_5", "ep_1_6"), allSelectable)

        val unwatchedSelectable = BatchEpisodeDownloadPlanner.computeSelectionForFilter(
            seasonEpisodes = season1,
            playbackMap = playbackMap,
            downloadedLookup = lookup,
            filter = BatchEpisodeFilter.UNWATCHED
        )
        assertEquals(setOf("ep_1_3", "ep_1_5", "ep_1_6"), unwatchedSelectable)

        val customDefault = BatchEpisodeDownloadPlanner.computeSelectionForFilter(
            seasonEpisodes = season1,
            playbackMap = playbackMap,
            downloadedLookup = lookup,
            filter = BatchEpisodeFilter.CUSTOM
        )
        assertEquals(emptySet<String>(), customDefault)
    }

    @Test
    fun testSelectBestBatchStreamRespectsQualityAndFallback() {
        val stream4k = StreamItem(
            name = "2160p WEB-DL",
            url = "https://cdn.example.com/4k.mp4",
            quality = "4K 2160p",
            addonName = "Alpha",
            addonId = "plugin:alpha"
        )
        val stream1080 = StreamItem(
            name = "1080p",
            url = "https://cdn.example.com/1080.mp4",
            quality = "1080p",
            addonName = "Alpha",
            addonId = "plugin:alpha"
        )
        val stream720 = StreamItem(
            name = "720p",
            url = "https://cdn.example.com/720.mp4",
            quality = "720p",
            addonName = "Beta",
            addonId = "plugin:beta"
        )
        val stream480 = StreamItem(
            name = "480p",
            url = "https://cdn.example.com/480.mp4",
            quality = "480p",
            addonName = "Beta",
            addonId = "plugin:beta"
        )
        val unsupported = StreamItem(
            name = "Broken FTP",
            url = "ftp://bad.example.com/video.mp4",
            quality = "1080p",
            addonName = "Bad",
            addonId = "plugin:bad"
        )

        val allStreams = listOf(unsupported, stream480, stream720, stream1080, stream4k)

        assertEquals(
            stream4k,
            BatchEpisodeDownloadPlanner.selectBestBatchDownloadStream(
                streams = allStreams,
                preferredQuality = BatchPreferredQuality.BEST,
                preferredProvider = null,
                preferSameProvider = false
            )
        )
        assertEquals(
            stream1080,
            BatchEpisodeDownloadPlanner.selectBestBatchDownloadStream(
                streams = allStreams,
                preferredQuality = BatchPreferredQuality.Q_1080P,
                preferredProvider = null,
                preferSameProvider = false
            )
        )
        assertEquals(
            stream720,
            BatchEpisodeDownloadPlanner.selectBestBatchDownloadStream(
                streams = allStreams,
                preferredQuality = BatchPreferredQuality.Q_720P,
                preferredProvider = null,
                preferSameProvider = false
            )
        )
        assertEquals(
            stream480,
            BatchEpisodeDownloadPlanner.selectBestBatchDownloadStream(
                streams = listOf(stream480, stream1080, stream4k),
                preferredQuality = BatchPreferredQuality.Q_720P,
                preferredProvider = null,
                preferSameProvider = false
            )
        )
        assertNull(
            BatchEpisodeDownloadPlanner.selectBestBatchDownloadStream(
                streams = listOf(unsupported),
                preferredQuality = BatchPreferredQuality.BEST,
                preferredProvider = null,
                preferSameProvider = false
            )
        )
    }

    @Test
    fun testSelectBestBatchStreamPrefersSameProviderWhenEnabled() {
        val providerA720 = StreamItem(
            name = "Alpha 720p",
            url = "https://cdn.example.com/a720.mp4",
            quality = "720p",
            addonName = "Alpha",
            addonId = "plugin:alpha",
            seeders = 10,
            matchScore = 0.8
        )
        val providerA1080 = StreamItem(
            name = "Alpha 1080p",
            url = "https://cdn.example.com/a1080.mp4",
            quality = "1080p",
            addonName = "Alpha",
            addonId = "plugin:alpha",
            seeders = 20,
            matchScore = 0.85
        )
        val providerB1080Direct = StreamItem(
            name = "Beta 1080p",
            url = "https://cdn.example.com/b1080.mp4",
            quality = "1080p",
            addonName = "Beta",
            addonId = "plugin:beta",
            isCached = true,
            seeders = 250,
            matchScore = 0.99
        )
        val providerB4k = StreamItem(
            name = "Beta 4K",
            url = "https://cdn.example.com/b4k.mp4",
            quality = "4K 2160p",
            addonName = "Beta",
            addonId = "plugin:beta",
            seeders = 150,
            matchScore = 0.95
        )

        // Exact quality match with provider affinity selects Alpha's 1080p over Beta's higher-seeded 1080p
        assertEquals(
            providerA1080,
            BatchEpisodeDownloadPlanner.selectBestBatchDownloadStream(
                streams = listOf(providerB1080Direct, providerA1080, providerB4k),
                preferredQuality = BatchPreferredQuality.Q_1080P,
                preferredProvider = "plugin:alpha",
                preferSameProvider = true
            )
        )

        // With Best + preferSameProvider enabled, locked provider stays consistent
        assertEquals(
            providerA1080,
            BatchEpisodeDownloadPlanner.selectBestBatchDownloadStream(
                streams = listOf(providerB4k, providerA720, providerA1080),
                preferredQuality = BatchPreferredQuality.BEST,
                preferredProvider = "plugin:alpha",
                preferSameProvider = true
            )
        )

        // With preferSameProvider disabled, highest resolution 4K wins
        assertEquals(
            providerB4k,
            BatchEpisodeDownloadPlanner.selectBestBatchDownloadStream(
                streams = listOf(providerB4k, providerA720, providerA1080),
                preferredQuality = BatchPreferredQuality.BEST,
                preferredProvider = "plugin:alpha",
                preferSameProvider = false
            )
        )
    }

    @Test
    fun testResolveEpisodeStreamTargetHandlesStandardAndCustomAddonIds() {
        val tmdbShow = sampleSeriesDetail(emptyList()).copy(
            id = "tmdb:1399",
            title = "Standard Show"
        )
        val epWithoutData = EpisodeItem(
            id = "ep_1",
            title = "Pilot",
            seasonNumber = 1,
            episodeNumber = 1,
            data = ""
        )
        val epWithColonData = EpisodeItem(
            id = "ep_2",
            title = "Second",
            seasonNumber = 1,
            episodeNumber = 2,
            data = "tmdb:tt0944947:1:2"
        )
        val epWithCustomAddonId = EpisodeItem(
            id = "cncverse:series:ep5",
            title = "Custom Ep 5",
            seasonNumber = 1,
            episodeNumber = 5,
            data = "addon:cncverse:ep5"
        )

        val targetDefault = BatchEpisodeDownloadPlanner.resolveEpisodeStreamTarget(tmdbShow, epWithoutData)
        assertEquals("tmdb:1399", targetDefault.videoId)
        assertEquals(1, targetDefault.season)
        assertEquals(1, targetDefault.episode)
        assertFalse(targetDefault.isCustomEpisodeId)

        val targetColon = BatchEpisodeDownloadPlanner.resolveEpisodeStreamTarget(tmdbShow, epWithColonData)
        assertEquals("tt0944947", targetColon.videoId)
        assertEquals(1, targetColon.season)
        assertEquals(2, targetColon.episode)
        assertFalse(targetColon.isCustomEpisodeId)

        val targetCustom = BatchEpisodeDownloadPlanner.resolveEpisodeStreamTarget(tmdbShow, epWithCustomAddonId)
        assertEquals("addon:cncverse:ep5", targetCustom.videoId)
        assertNull(targetCustom.season)
        assertNull(targetCustom.episode)
        assertTrue(targetCustom.isCustomEpisodeId)
    }

    @Test
    fun testFindEpisodeDownloadDistinguishesActiveFailedAndCompletedStates() {
        val downloadingEp = episode(1, 1)
        val failedEp = episode(1, 2)
        val completedEp = episode(1, 3)
        val detail = sampleSeriesDetail(listOf(downloadingEp, failedEp, completedEp))

        val lookup = BatchEpisodeDownloadPlanner.buildDownloadedLookup(
            downloads = listOf(
                DownloadItem(
                    id = "d1",
                    mediaId = detail.id,
                    episodeId = downloadingEp.id,
                    title = detail.title,
                    state = DownloadState.DOWNLOADING,
                    seasonNumber = 1,
                    episodeNumber = 1
                ),
                DownloadItem(
                    id = "d2",
                    mediaId = detail.id,
                    episodeId = failedEp.id,
                    title = detail.title,
                    state = DownloadState.FAILED,
                    seasonNumber = 1,
                    episodeNumber = 2
                ),
                DownloadItem(
                    id = "d3",
                    mediaId = detail.id,
                    episodeId = completedEp.id,
                    title = detail.title,
                    state = DownloadState.COMPLETED,
                    seasonNumber = 1,
                    episodeNumber = 3
                )
            ),
            detail = detail
        )

        val d1 = BatchEpisodeDownloadPlanner.findEpisodeDownload(downloadingEp, lookup)
        val d2 = BatchEpisodeDownloadPlanner.findEpisodeDownload(failedEp, lookup)
        val d3 = BatchEpisodeDownloadPlanner.findEpisodeDownload(completedEp, lookup)

        assertEquals(DownloadState.DOWNLOADING, d1?.state)
        assertEquals(DownloadState.FAILED, d2?.state)
        assertEquals(DownloadState.COMPLETED, d3?.state)
        // Active DOWNLOADING & COMPLETED count as already queued/downloaded, while FAILED can be re-queued
        assertTrue(BatchEpisodeDownloadPlanner.isEpisodeAlreadyDownloaded(downloadingEp, lookup))
        assertFalse(BatchEpisodeDownloadPlanner.isEpisodeAlreadyDownloaded(failedEp, lookup))
        assertTrue(BatchEpisodeDownloadPlanner.isEpisodeAlreadyDownloaded(completedEp, lookup))
    }
}
