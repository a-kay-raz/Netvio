package com.netvio.ui.details.downloads

import com.netvio.core.addons.isCustomStremioAddonId
import com.netvio.core.model.EpisodeItem
import com.netvio.core.model.MediaDetail
import com.netvio.core.streams.StreamItem
import com.netvio.data.local.PlaybackHistoryEntity
import com.netvio.download.DownloadItem
import com.netvio.download.DownloadState
import java.util.Locale

enum class BatchEpisodeFilter(val label: String) {
    UNWATCHED("Unwatched"),
    ALL("All"),
    CUSTOM("Custom")
}

enum class BatchPreferredQuality(val label: String, val targetHeight: Int?) {
    BEST("Best", null),
    Q_1080P("1080p", 1080),
    Q_720P("720p", 720),
    Q_480P("480p", 480)
}

data class BatchDownloadState(
    val isRunning: Boolean = false,
    val currentEpisodeLabel: String? = null,
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val enqueuedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val statusMessage: String? = null
)

internal data class DownloadedEpisodeLookup(
    val episodeIds: Set<String>,
    val seasonEpisodeCoords: Set<Pair<Int, Int>>,
    val byEpisodeId: Map<String, DownloadItem> = emptyMap(),
    val bySeasonEpisode: Map<Pair<Int, Int>, DownloadItem> = emptyMap()
)

internal data class EpisodeStreamRequestTarget(
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val isCustomEpisodeId: Boolean
)

/**
 * Pure filtering, download-state lookup, and quality/provider stream-selection rules
 * for batch series episode downloads.
 */
internal object BatchEpisodeDownloadPlanner {

    fun isEpisodeWatched(playback: PlaybackHistoryEntity?): Boolean {
        if (playback == null) return false
        return playback.isCompleted ||
            (playback.durationMs > 0L && playback.currentPositionMs >= (playback.durationMs * 0.9f).toLong())
    }

    /**
     * Resolves how [episode] should be queried in [com.netvio.core.streams.StreamsRepository],
     * preserving per-episode custom IDs (e.g. Stremio/CNCVerse video IDs) alongside TMDB/IMDb season+episode pairs.
     */
    fun resolveEpisodeStreamTarget(
        detail: MediaDetail,
        episode: EpisodeItem
    ): EpisodeStreamRequestTarget {
        val rawData = episode.data?.trim()?.takeIf { it.isNotBlank() }
        if (rawData != null) {
            val cleanData = rawData.removePrefix("tmdb:").removePrefix("tmdb/").trim()
            val parts = cleanData.split(":")
            val parsedSeason = parts.getOrNull(parts.size - 2)?.toIntOrNull()
            val parsedEpisode = parts.getOrNull(parts.size - 1)?.toIntOrNull()
            if (parts.size >= 3 && parsedSeason != null && parsedEpisode != null) {
                val baseVideoId = parts.dropLast(2).joinToString(":")
                return EpisodeStreamRequestTarget(
                    videoId = baseVideoId,
                    season = parsedSeason,
                    episode = parsedEpisode,
                    isCustomEpisodeId = isCustomStremioAddonId(baseVideoId)
                )
            }
            if (isCustomStremioAddonId(cleanData) && !cleanData.equals(detail.id, ignoreCase = true)) {
                return EpisodeStreamRequestTarget(
                    videoId = cleanData,
                    season = null,
                    episode = null,
                    isCustomEpisodeId = true
                )
            }
        }
        return EpisodeStreamRequestTarget(
            videoId = detail.id,
            season = episode.seasonNumber,
            episode = episode.episodeNumber,
            isCustomEpisodeId = isCustomStremioAddonId(detail.id)
        )
    }

    fun buildDownloadedLookup(
        downloads: List<DownloadItem>,
        detail: MediaDetail
    ): DownloadedEpisodeLookup {
        val ids = HashSet<String>()
        val coords = HashSet<Pair<Int, Int>>()
        val byEpisodeId = HashMap<String, DownloadItem>()
        val bySeasonEp = HashMap<Pair<Int, Int>, DownloadItem>()
        val activeStates = setOf(
            DownloadState.QUEUED,
            DownloadState.DOWNLOADING,
            DownloadState.PAUSED,
            DownloadState.COMPLETED
        )
        for (item in downloads) {
            val matchesSeries = item.mediaId == detail.id ||
                item.title.equals(detail.title, ignoreCase = true) ||
                item.title.contains(detail.title, ignoreCase = true)
            if (!matchesSeries) continue

            item.episodeId?.let { byEpisodeId[it] = item }
            if (item.seasonNumber != null && item.episodeNumber != null) {
                bySeasonEp[item.seasonNumber to item.episodeNumber] = item
            }

            if (item.state !in activeStates) continue
            item.episodeId?.let { ids.add(it) }
            if (item.seasonNumber != null && item.episodeNumber != null) {
                coords.add(item.seasonNumber to item.episodeNumber)
            }
        }
        return DownloadedEpisodeLookup(
            episodeIds = ids,
            seasonEpisodeCoords = coords,
            byEpisodeId = byEpisodeId,
            bySeasonEpisode = bySeasonEp
        )
    }

    fun findEpisodeDownload(
        episode: EpisodeItem,
        lookup: DownloadedEpisodeLookup
    ): DownloadItem? {
        return lookup.byEpisodeId[episode.id]
            ?: episode.data?.let { lookup.byEpisodeId[it] }
            ?: lookup.bySeasonEpisode[episode.seasonNumber to episode.episodeNumber]
    }

    fun isEpisodeAlreadyDownloaded(
        episode: EpisodeItem,
        lookup: DownloadedEpisodeLookup
    ): Boolean {
        val activeStates = setOf(
            DownloadState.QUEUED,
            DownloadState.DOWNLOADING,
            DownloadState.PAUSED,
            DownloadState.COMPLETED
        )
        val matchedItem = findEpisodeDownload(episode, lookup)
        if (matchedItem != null) {
            return matchedItem.state in activeStates
        }
        if (lookup.episodeIds.contains(episode.id)) return true
        return lookup.seasonEpisodeCoords.contains(episode.seasonNumber to episode.episodeNumber)
    }

    fun episodesForSeason(
        allEpisodes: List<EpisodeItem>,
        season: Int
    ): List<EpisodeItem> = allEpisodes
        .filter { it.seasonNumber == season }
        .sortedBy { it.episodeNumber }

    fun computeSelectionForFilter(
        seasonEpisodes: List<EpisodeItem>,
        playbackMap: Map<Pair<Int, Int>, PlaybackHistoryEntity>,
        downloadedLookup: DownloadedEpisodeLookup,
        filter: BatchEpisodeFilter
    ): Set<String> {
        if (filter == BatchEpisodeFilter.CUSTOM) return emptySet()
        return seasonEpisodes.asSequence()
            .filter { !isEpisodeAlreadyDownloaded(it, downloadedLookup) }
            .filter { ep ->
                when (filter) {
                    BatchEpisodeFilter.ALL -> true
                    BatchEpisodeFilter.UNWATCHED -> !isEpisodeWatched(playbackMap[ep.seasonNumber to ep.episodeNumber])
                    BatchEpisodeFilter.CUSTOM -> false
                }
            }
            .map { it.id }
            .toSet()
    }

    fun parseStreamHeight(stream: StreamItem): Int? {
        val resolved = DownloadTokens.extractResolution(
            stream.quality,
            listOfNotNull(stream.name, stream.title).joinToString(" ")
        ) ?: return null
        val lower = resolved.lowercase(Locale.US)
        return when {
            lower.contains("2160") || lower.contains("4k") -> 2160
            lower.contains("1440") -> 1440
            lower.contains("1080") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            else -> Regex("""(\d{3,4})p""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    fun providerKey(stream: StreamItem): String? =
        stream.addonId.takeIf { it.isNotBlank() }
            ?: stream.addonName.takeIf { it.isNotBlank() }
            ?: stream.sourceName?.takeIf { it.isNotBlank() }

    private fun matchesProvider(stream: StreamItem, targetProvider: String): Boolean {
        return stream.addonId.equals(targetProvider, ignoreCase = true) ||
            stream.addonName.equals(targetProvider, ignoreCase = true) ||
            (stream.sourceName?.equals(targetProvider, ignoreCase = true) == true)
    }

    private fun computeQualityScore(height: Int?, preferredQuality: BatchPreferredQuality): Int {
        val target = preferredQuality.targetHeight ?: return (height ?: 0)
        if (height == null) return 0
        if (height == target) return 1_000_000
        val diff = kotlin.math.abs(height - target)
        val underTargetTieBreaker = if (height <= target) 1 else 0
        return (500_000 - diff * 100 + underTargetTieBreaker).coerceAtLeast(1)
    }

    /**
     * Selects the highest-priority downloadable stream matching [preferredQuality] and,
     * when [preferSameProvider] is active, [preferredProvider].
     */
    fun selectBestBatchDownloadStream(
        streams: List<StreamItem>,
        preferredQuality: BatchPreferredQuality,
        preferredProvider: String?,
        preferSameProvider: Boolean
    ): StreamItem? {
        val candidates = streams.filter { stream ->
            stream.isSupportedDownloadUrl() ||
                (stream.isTorrentStream && !stream.effectiveMagnetOrHash.isNullOrBlank())
        }
        if (candidates.isEmpty()) return null

        val targetHeight = preferredQuality.targetHeight
        val lockProvider = if (preferSameProvider) preferredProvider?.takeIf { it.isNotBlank() } else null

        val comparator = Comparator<StreamItem> { a, b ->
            val ha = parseStreamHeight(a)
            val hb = parseStreamHeight(b)

            if (targetHeight != null) {
                val exactCmp = (hb == targetHeight).compareTo(ha == targetHeight)
                if (exactCmp != 0) return@Comparator exactCmp
            }

            if (lockProvider != null) {
                val provCmp = matchesProvider(b, lockProvider).compareTo(matchesProvider(a, lockProvider))
                if (provCmp != 0) return@Comparator provCmp
            }

            val qCmp = computeQualityScore(hb, preferredQuality)
                .compareTo(computeQualityScore(ha, preferredQuality))
            if (qCmp != 0) return@Comparator qCmp

            val directCmp = (b.playableDirectUrl != null || b.isCached)
                .compareTo(a.playableDirectUrl != null || a.isCached)
            if (directCmp != 0) return@Comparator directCmp

            val seedCmp = (b.seeders ?: 0).compareTo(a.seeders ?: 0)
            if (seedCmp != 0) return@Comparator seedCmp

            b.matchScore.compareTo(a.matchScore)
        }

        return candidates.sortedWith(comparator).firstOrNull()
    }
}
