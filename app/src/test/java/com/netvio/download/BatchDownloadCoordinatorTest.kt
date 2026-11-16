package com.netvio.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.netvio.core.model.EpisodeItem
import com.netvio.core.model.MediaDetail
import com.netvio.core.model.MediaType
import com.netvio.core.streams.StreamsRepository
import com.netvio.ui.details.downloads.BatchDownloadCoordinator
import com.netvio.ui.details.downloads.BatchPreferredQuality
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatchDownloadCoordinatorTest {

    @Test
    fun testCoordinatorStatePersistsAcrossObserversAndNavAliases() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coordinator = BatchDownloadCoordinator.getInstance(context)
        val detail = MediaDetail(
            id = "tmdb:999888",
            title = "Persistent Series",
            url = "https://example.com/series",
            posterUrl = null,
            backdropUrl = null,
            type = MediaType.TV_SERIES,
            year = 2025,
            synopsis = null,
            imdbId = "tt9998881",
            episodes = listOf(
                EpisodeItem(id = "ep_1", title = "Ep 1", seasonNumber = 1, episodeNumber = 1, data = "ep_1")
            )
        )

        coordinator.startBatch(
            detail = detail,
            episodes = detail.episodes,
            preferredQuality = BatchPreferredQuality.BEST,
            preferSameProvider = true,
            navMediaId = "999888"
        )

        // Simulates navigating back and creating a new MediaDetailsViewModel on return
        val restoredByCanonical = coordinator.observeState(detail.id, aliasId = "999888").first { !it.isRunning }
        val restoredByAlias = coordinator.getCurrentState(primaryId = "999888")

        assertFalse(restoredByCanonical.isRunning)
        assertEquals(1, restoredByCanonical.totalCount)
        assertEquals(restoredByCanonical, restoredByAlias)
    }

    @Test
    fun testFetchStreamsSnapshotDoesNotResetForegroundStreamsState() = runBlocking {
        StreamsRepository.clear()
        val before = StreamsRepository.uiState.value

        val streams = StreamsRepository.fetchStreamsSnapshot(
            type = "tv",
            videoId = "tmdb:1399",
            season = 1,
            episode = 1
        )

        val after = StreamsRepository.uiState.value
        assertEquals(emptyList<Any>(), streams)
        assertEquals(before.requestToken, after.requestToken)
        assertNull(after.emptyStateReason)
    }
}
