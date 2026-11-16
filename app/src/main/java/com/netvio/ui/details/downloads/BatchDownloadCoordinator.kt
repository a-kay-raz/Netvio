package com.netvio.ui.details.downloads

import android.content.Context
import com.netvio.core.model.EpisodeItem
import com.netvio.core.model.MediaDetail
import com.netvio.core.model.MediaType
import com.netvio.core.p2p.P2pStreamingEngine
import com.netvio.core.streams.StreamsRepository
import com.netvio.core.util.TmdbBridge
import com.netvio.data.preferences.PreferencesRepository
import com.netvio.download.DownloadRepository
import com.netvio.download.StoragePreflightResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

internal data class BatchStatusNotice(
    val mediaId: String,
    val message: String,
    val aliasId: String? = null
)

/**
 * Application-scoped coordinator for multi-episode batch download resolution & queuing.
 * Survives [com.netvio.ui.details.MediaDetailsViewModel] disposal so navigating away from
 * Media Details and returning later preserves live progress and queued episodes.
 */
internal class BatchDownloadCoordinator private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _statesByMedia = MutableStateFlow<Map<String, BatchDownloadState>>(emptyMap())
    val statesByMedia: StateFlow<Map<String, BatchDownloadState>> = _statesByMedia.asStateFlow()

    private val _notices = MutableSharedFlow<BatchStatusNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<BatchStatusNotice> = _notices.asSharedFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val aliasToCanonical = ConcurrentHashMap<String, String>()

    fun observeState(primaryId: String, aliasId: String? = null): Flow<BatchDownloadState> {
        return statesByMedia
            .map { states ->
                resolveCurrentState(states, primaryId, aliasId) ?: BatchDownloadState()
            }
            .distinctUntilChanged()
    }

    fun getCurrentState(primaryId: String, aliasId: String? = null): BatchDownloadState {
        return resolveCurrentState(_statesByMedia.value, primaryId, aliasId) ?: BatchDownloadState()
    }

    fun startBatch(
        detail: MediaDetail,
        episodes: List<EpisodeItem>,
        preferredQuality: BatchPreferredQuality,
        preferSameProvider: Boolean,
        navMediaId: String? = null
    ) {
        if (episodes.isEmpty()) return

        val canonicalId = detail.id
        if (!navMediaId.isNullOrBlank() && navMediaId != canonicalId) {
            aliasToCanonical[navMediaId] = canonicalId
        }

        activeJobs.remove(canonicalId)?.cancel()

        val sortedEpisodes = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
        val downloadRepository = DownloadRepository.getInstance(appContext)
        val prefsRepository = PreferencesRepository.getInstance(appContext)

        updateState(
            canonicalId,
            BatchDownloadState(
                isRunning = true,
                currentEpisodeLabel = sortedEpisodes.firstOrNull()?.let {
                    String.format(Locale.US, "S%02dE%02d", it.seasonNumber, it.episodeNumber)
                },
                processedCount = 0,
                totalCount = sortedEpisodes.size
            )
        )

        val job = scope.launch {
            var enqueuedCount = 0
            var skippedCount = 0
            var failedCount = 0
            var lockedProvider: String? = null

            try {
                P2pStreamingEngine.prewarmEngine(appContext)

                val debrid = resolveEffectiveDebridCredentials(
                    debridEnabled = prefsRepository.debridEnabled.first(),
                    activeProviderId = prefsRepository.activeDebridProvider.first(),
                    realDebridKey = prefsRepository.realDebridApiKey.first(),
                    allDebridKey = prefsRepository.allDebridApiKey.first(),
                    torBoxKey = prefsRepository.torBoxApiKey.first()
                )

                val resolvedImdb = detail.imdbId?.takeIf { it.startsWith("tt") }
                    ?: TmdbBridge.resolveImdbId(type = detail.type, tmdbId = detail.id)

                val mediaType = if (detail.type == MediaType.TV_SERIES || detail.type == MediaType.ANIME) "tv" else "movie"

                for ((index, ep) in sortedEpisodes.withIndex()) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val epLabel = String.format(Locale.US, "S%02dE%02d", ep.seasonNumber, ep.episodeNumber)
                    updateState(
                        canonicalId,
                        BatchDownloadState(
                            isRunning = true,
                            currentEpisodeLabel = epLabel,
                            processedCount = index,
                            totalCount = sortedEpisodes.size,
                            enqueuedCount = enqueuedCount,
                            skippedCount = skippedCount,
                            failedCount = failedCount
                        )
                    )

                    val dlId = "${detail.id}_s${ep.seasonNumber}e${ep.episodeNumber}"
                    val currentDownloadedLookup = BatchEpisodeDownloadPlanner.buildDownloadedLookup(
                        downloadRepository.downloads.value,
                        detail
                    )
                    if (BatchEpisodeDownloadPlanner.isEpisodeAlreadyDownloaded(ep, currentDownloadedLookup) ||
                        downloadRepository.isDownloading(dlId)
                    ) {
                        skippedCount++
                        continue
                    }

                    if (index > 0) {
                        delay(350L)
                    }

                    val target = BatchEpisodeDownloadPlanner.resolveEpisodeStreamTarget(detail, ep)
                    val epImdb = if (target.isCustomEpisodeId) null else (resolvedImdb ?: detail.imdbId)

                    var availableStreams = emptyList<com.netvio.core.streams.StreamItem>()
                    for (attempt in 0..1) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        availableStreams = StreamsRepository.fetchStreamsSnapshot(
                            type = mediaType,
                            videoId = target.videoId,
                            season = target.season,
                            episode = target.episode,
                            title = detail.title,
                            year = detail.year,
                            imdbId = epImdb
                        )
                        if (availableStreams.isNotEmpty() || attempt == 1) break
                        delay(1_000L)
                    }

                    val chosenStream = BatchEpisodeDownloadPlanner.selectBestBatchDownloadStream(
                        streams = availableStreams,
                        preferredQuality = preferredQuality,
                        preferredProvider = lockedProvider,
                        preferSameProvider = preferSameProvider
                    )

                    if (chosenStream == null) {
                        failedCount++
                        continue
                    }

                    if (preferSameProvider && lockedProvider == null) {
                        lockedProvider = BatchEpisodeDownloadPlanner.providerKey(chosenStream)
                    }

                    val outcome = resolveAndEnqueueStream(
                        downloadRepository = downloadRepository,
                        detail = detail,
                        episode = ep,
                        stream = chosenStream,
                        debrid = debrid
                    )
                    when (outcome.preflight) {
                        is StoragePreflightResult.Sufficient -> enqueuedCount++
                        is StoragePreflightResult.InsufficientSpace -> {
                            val stopMsg = "Stopped at $epLabel: insufficient storage space"
                            updateState(
                                canonicalId,
                                BatchDownloadState(
                                    isRunning = false,
                                    processedCount = index,
                                    totalCount = sortedEpisodes.size,
                                    enqueuedCount = enqueuedCount,
                                    skippedCount = skippedCount,
                                    failedCount = failedCount,
                                    statusMessage = stopMsg
                                )
                            )
                            _notices.tryEmit(BatchStatusNotice(canonicalId, stopMsg, navMediaId))
                            return@launch
                        }
                        is StoragePreflightResult.UnsupportedStream -> failedCount++
                    }
                }

                val parts = mutableListOf("Queued $enqueuedCount ${if (enqueuedCount == 1) "episode" else "episodes"}")
                if (skippedCount > 0) parts.add("$skippedCount already downloaded")
                if (failedCount > 0) parts.add("$failedCount unavailable")
                val summary = parts.joinToString(" · ")

                updateState(
                    canonicalId,
                    BatchDownloadState(
                        isRunning = false,
                        processedCount = sortedEpisodes.size,
                        totalCount = sortedEpisodes.size,
                        enqueuedCount = enqueuedCount,
                        skippedCount = skippedCount,
                        failedCount = failedCount,
                        statusMessage = summary
                    )
                )
                _notices.tryEmit(BatchStatusNotice(canonicalId, summary, navMediaId))
            } catch (_: CancellationException) {
                val cur = _statesByMedia.value[canonicalId] ?: BatchDownloadState()
                updateState(
                    canonicalId,
                    cur.copy(
                        isRunning = false,
                        statusMessage = "Batch download cancelled ($enqueuedCount queued)"
                    )
                )
            } catch (t: Throwable) {
                val err = "Batch download failed: ${t.message ?: "Unknown error"}"
                val cur = _statesByMedia.value[canonicalId] ?: BatchDownloadState()
                updateState(
                    canonicalId,
                    cur.copy(
                        isRunning = false,
                        statusMessage = err
                    )
                )
                _notices.tryEmit(BatchStatusNotice(canonicalId, err, navMediaId))
            } finally {
                val currentJob = coroutineContext[Job]
                if (currentJob != null && activeJobs[canonicalId] === currentJob) {
                    activeJobs.remove(canonicalId)
                }
            }
        }
        activeJobs[canonicalId] = job
    }

    fun cancelBatch(primaryId: String, aliasId: String? = null) {
        val targetKey = resolveCanonicalKey(primaryId, aliasId)
        activeJobs.remove(targetKey)?.cancel()
        val cur = _statesByMedia.value[targetKey]
        if (cur != null && cur.isRunning) {
            updateState(
                targetKey,
                cur.copy(
                    isRunning = false,
                    statusMessage = "Batch download cancelled (${cur.enqueuedCount} queued)"
                )
            )
        }
    }

    private fun resolveCanonicalKey(primaryId: String, aliasId: String?): String {
        val states = _statesByMedia.value
        if (states.containsKey(primaryId) || activeJobs.containsKey(primaryId)) return primaryId
        val mappedPrimary = aliasToCanonical[primaryId]
        if (mappedPrimary != null) return mappedPrimary
        if (aliasId != null) {
            if (states.containsKey(aliasId) || activeJobs.containsKey(aliasId)) return aliasId
            aliasToCanonical[aliasId]?.let { return it }
        }
        return primaryId
    }

    private fun resolveCurrentState(
        states: Map<String, BatchDownloadState>,
        primaryId: String,
        aliasId: String?
    ): BatchDownloadState? {
        return states[primaryId]
            ?: aliasToCanonical[primaryId]?.let { states[it] }
            ?: aliasId?.let { states[it] ?: aliasToCanonical[it]?.let { mapped -> states[mapped] } }
    }

    private fun updateState(mediaId: String, newState: BatchDownloadState) {
        _statesByMedia.update { current ->
            current + (mediaId to newState)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: BatchDownloadCoordinator? = null

        fun getInstance(context: Context): BatchDownloadCoordinator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BatchDownloadCoordinator(context).also { INSTANCE = it }
            }
        }
    }
}
