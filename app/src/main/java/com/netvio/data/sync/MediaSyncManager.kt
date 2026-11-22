package com.netvio.data.sync

import android.content.Context
import android.util.Log
import com.netvio.core.model.MediaDetail
import com.netvio.core.model.MediaItem
import com.netvio.core.network.DohDns
import com.netvio.core.sync.simkl.SimklClient
import com.netvio.core.sync.simkl.SimklHistoryEpisode
import com.netvio.core.sync.simkl.SimklHistoryPayload
import com.netvio.core.sync.simkl.SimklHistorySeason
import com.netvio.core.sync.simkl.SimklHistoryShow
import com.netvio.core.sync.simkl.SimklIds
import com.netvio.core.sync.simkl.SimklSyncItem
import com.netvio.core.sync.simkl.SimklSyncPayload
import com.netvio.core.sync.trakt.*
import com.netvio.core.util.TmdbBridge
import com.netvio.data.local.AppDatabase
import com.netvio.data.local.PlaybackHistoryEntity
import com.netvio.data.local.WatchlistEntity
import com.netvio.data.preferences.PreferencesRepository
import com.netvio.data.profile.ProfileManager
import com.netvio.data.repository.Playlist
import com.netvio.data.repository.PlaylistItem
import com.netvio.data.repository.PlaylistRepository
import com.netvio.data.sync.supabase.SupabaseSyncManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified MediaSyncManager orchestrating full two-way synchronization across Trakt.tv and Simkl.
 *
 * Coordinated with [SupabaseSyncManager] via [SyncCoordinator.dataMutex] and respects
 * [TrackerReconciliationPolicy] so deletions, dismissals, profile isolation, and single-flight
 * OAuth token refreshes behave safely under concurrent user activity.
 */
class MediaSyncManager private constructor(
    private val context: Context
) {
    enum class TrackerSyncTarget {
        ALL,
        TRAKT,
        SIMKL;

        fun includesTrakt(): Boolean = this == ALL || this == TRAKT
        fun includesSimkl(): Boolean = this == ALL || this == SIMKL
    }

    private data class SubSyncOutcome(
        val error: String? = null,
        val didMutateLocal: Boolean = false,
        val activeToken: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val db = AppDatabase.getInstance(context)
    private val watchlistDao = db.watchlistDao()
    private val playbackHistoryDao = db.playbackHistoryDao()
    private val playlistRepository = PlaylistRepository.getInstance(context)
    private val preferencesRepository = PreferencesRepository.getInstance(context)
    private val profileManager = ProfileManager.getInstance(context)

    private val traktClient = TraktClient.getInstance()
    private val simklClient = SimklClient.getInstance()
    private val httpClient = DohDns.metadataClient.newBuilder()
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 12
                maxRequestsPerHost = 4
            }
        )
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val syncMutex = Mutex()
    private val pendingTarget = AtomicReference<TrackerSyncTarget?>(null)
    private val traktRefreshMutex = Mutex()
    private val traktAuthGeneration = AtomicLong(0L)
    private val simklAuthGeneration = AtomicLong(0L)
    private val posterRepairRunning = AtomicBoolean(false)

    private val hydrationSemaphore = Semaphore(4)
    private val hydratedCache = ConcurrentHashMap<String, HydratedMediaMeta>()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isTraktSyncing = MutableStateFlow(false)
    val isTraktSyncing: StateFlow<Boolean> = _isTraktSyncing.asStateFlow()

    private val _isSimklSyncing = MutableStateFlow(false)
    val isSimklSyncing: StateFlow<Boolean> = _isSimklSyncing.asStateFlow()

    private val _syncStatusMessage = MutableStateFlow<String?>(null)
    val syncStatusMessage: StateFlow<String?> = _syncStatusMessage.asStateFlow()

    private val _traktStatusMessage = MutableStateFlow<String?>(null)
    val traktStatusMessage: StateFlow<String?> = _traktStatusMessage.asStateFlow()

    private val _simklStatusMessage = MutableStateFlow<String?>(null)
    val simklStatusMessage: StateFlow<String?> = _simklStatusMessage.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _traktSyncError = MutableStateFlow<String?>(null)
    val traktSyncError: StateFlow<String?> = _traktSyncError.asStateFlow()

    private val _simklSyncError = MutableStateFlow<String?>(null)
    val simklSyncError: StateFlow<String?> = _simklSyncError.asStateFlow()

    private data class HydratedMediaMeta(
        val mediaId: String,
        val tmdbId: Long?,
        val imdbId: String?,
        val title: String,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val rating: Double?,
        val durationMs: Long?
    )

    private fun currentProfileId(): String =
        profileManager.activeProfile.value?.id ?: "default_user"

    private fun isTrackerSyncAllowed(): Boolean =
        TrackerReconciliationPolicy.shouldSyncTrackersForProfile(profileManager.activeProfile.value?.type)

    private fun mergeTargets(existing: TrackerSyncTarget?, incoming: TrackerSyncTarget): TrackerSyncTarget {
        if (existing == null || existing == incoming) return incoming
        return TrackerSyncTarget.ALL
    }

    private fun enqueuePendingTarget(target: TrackerSyncTarget) {
        pendingTarget.updateAndGet { existing -> mergeTargets(existing, target) }
    }

    private fun isTraktSessionStillValid(expectedGen: Long): Boolean =
        traktAuthGeneration.get() == expectedGen

    private fun isSimklSessionStillValid(expectedGen: Long): Boolean =
        simklAuthGeneration.get() == expectedGen

    fun notifyTraktDisconnected() {
        traktAuthGeneration.incrementAndGet()
        _isTraktSyncing.value = false
        _traktSyncError.value = null
        _traktStatusMessage.value = null
        _isSyncing.value = _isSimklSyncing.value
    }

    fun notifySimklDisconnected() {
        simklAuthGeneration.incrementAndGet()
        _isSimklSyncing.value = false
        _simklSyncError.value = null
        _simklStatusMessage.value = null
        _isSyncing.value = _isTraktSyncing.value
    }

    private suspend fun fetchLightweightTmdbMeta(
        rawId: String,
        fallbackTitle: String,
        fallbackYear: Int?,
        isTv: Boolean
    ): HydratedMediaMeta? = withContext(Dispatchers.IO) {
        val cleanId = rawId.trim().removePrefix("tmdb:").removePrefix("tmdb/").substringBefore(':').substringBefore('/')
        if (cleanId.isBlank()) return@withContext null

        val primaryEndpoint = if (isTv) "tv" else "movie"
        val altEndpoint = if (isTv) "movie" else "tv"

        for (attempt in 1..2) {
            for (endpoint in listOf(primaryEndpoint, altEndpoint)) {
                try {
                    val apiKey = TmdbBridge.DEFAULT_API_KEY
                    val url = "${TmdbBridge.BASE_URL}/$endpoint/$cleanId?append_to_response=external_ids&api_key=$apiKey"
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", TmdbBridge.DEFAULT_USER_AGENT)
                        .build()

                    val (code, body) = httpClient.newCall(req).execute().use { resp ->
                        resp.code to (resp.body?.string() ?: "")
                    }

                    if (code == 429) {
                        delay(350L * attempt)
                        break
                    }
                    if (code == 404) {
                        continue
                    }
                    if (code !in 200..299 || body.isBlank()) {
                        break
                    }

                    val root = JSONObject(body)
                    val numericId = root.optLong("id", 0L).takeIf { it > 0L } ?: cleanId.toLongOrNull()
                    val resolvedTitle = root.optString("title").ifBlank { root.optString("name") }.ifBlank { fallbackTitle }
                    val posterPath = root.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
                    val backdropPath = root.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
                    val posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w780$it" }
                    val backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" } ?: posterUrl

                    val dateStr = root.optString("release_date").ifBlank { root.optString("first_air_date") }
                    val yearVal = dateStr.take(4).toIntOrNull() ?: fallbackYear
                    val voteAvg = root.optDouble("vote_average", 0.0).takeIf { !it.isNaN() && it > 0.0 }

                    val runtimeMins = root.optInt("runtime", 0).takeIf { it > 0 }
                        ?: root.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
                    val durationMs = runtimeMins?.let { it * 60_000L }

                    val extIds = root.optJSONObject("external_ids")
                    val imdbStr = extIds?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
                        ?: root.optString("imdb_id").takeIf { it.startsWith("tt") }

                    val finalIdStr = numericId?.toString() ?: cleanId
                    if (imdbStr != null && numericId != null) {
                        TmdbBridge.recordIdMapping(numericId.toString(), imdbStr, endpoint == "tv")
                    }

                    return@withContext HydratedMediaMeta(
                        mediaId = finalIdStr,
                        tmdbId = numericId,
                        imdbId = imdbStr,
                        title = resolvedTitle,
                        year = yearVal,
                        posterUrl = posterUrl,
                        backdropUrl = backdropUrl,
                        rating = voteAvg,
                        durationMs = durationMs
                    )
                } catch (_: Exception) {}
            }
        }
        null
    }

    private suspend fun hydrateMediaMeta(
        rawMediaId: String?,
        tmdbId: Long?,
        imdbId: String?,
        title: String,
        year: Int?,
        isTv: Boolean,
        fetchRemoteIfUncached: Boolean = true
    ): HydratedMediaMeta {
        val cleanRaw = rawMediaId?.trim()?.removePrefix("tmdb:")?.removePrefix("tmdb/")
        val initialTmdb = tmdbId ?: cleanRaw?.toLongOrNull()
        val initialImdb = imdbId?.takeIf { it.startsWith("tt") } ?: cleanRaw?.takeIf { it.startsWith("tt") }
        val cacheKey = "${if (isTv) "tv" else "movie"}:${initialTmdb ?: initialImdb ?: "${title.lowercase()}_${year ?: 0}"}"

        hydratedCache[cacheKey]?.takeIf { !it.posterUrl.isNullOrBlank() }?.let { return it }

        var resolvedTmdbStr: String? = initialTmdb?.toString()
        var resolvedImdbStr: String? = initialImdb

        if (resolvedTmdbStr != null) {
            TmdbBridge.getCachedEnrichedDetails(resolvedTmdbStr, isTv)?.let { cached ->
                if (!cached.posterUrl.isNullOrBlank()) {
                    val meta = HydratedMediaMeta(
                        mediaId = resolvedTmdbStr,
                        tmdbId = resolvedTmdbStr.toLongOrNull(),
                        imdbId = cached.imdbId ?: resolvedImdbStr,
                        title = cached.title.ifBlank { title },
                        year = cached.year ?: year,
                        posterUrl = cached.posterUrl,
                        backdropUrl = cached.backdropUrl ?: cached.posterUrl,
                        rating = cached.rating?.toDoubleOrNull(),
                        durationMs = cached.runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L }
                    )
                    hydratedCache[cacheKey] = meta
                    return meta
                }
            }
        }

        if (!fetchRemoteIfUncached) {
            val fallbackId = resolvedTmdbStr ?: resolvedImdbStr ?: cleanRaw ?: title
            return HydratedMediaMeta(
                mediaId = fallbackId,
                tmdbId = resolvedTmdbStr?.toLongOrNull(),
                imdbId = resolvedImdbStr,
                title = title,
                year = year,
                posterUrl = null,
                backdropUrl = null,
                rating = null,
                durationMs = null
            )
        }

        return hydrationSemaphore.withPermit {
            hydratedCache[cacheKey]?.takeIf { !it.posterUrl.isNullOrBlank() }?.let { return@withPermit it }
            try {
                if (resolvedTmdbStr == null && !resolvedImdbStr.isNullOrBlank()) {
                    resolvedTmdbStr = TmdbBridge.findTmdbIdByImdb(httpClient, resolvedImdbStr!!, isTv)
                }
                if (resolvedTmdbStr == null && title.isNotBlank()) {
                    resolvedTmdbStr = TmdbBridge.searchTmdbId(httpClient, title, year, isTv)
                }

                val lookupId = resolvedTmdbStr ?: resolvedImdbStr
                val cachedEnriched = if (!lookupId.isNullOrBlank()) {
                    TmdbBridge.getCachedEnrichedDetails(lookupId, isTv)
                } else null

                val meta = if (cachedEnriched != null && !cachedEnriched.posterUrl.isNullOrBlank()) {
                    val finalTmdbStr = cachedEnriched.tmdbId.takeIf { it.isNotBlank() } ?: resolvedTmdbStr
                    val finalImdbStr = cachedEnriched.imdbId?.takeIf { it.startsWith("tt") } ?: resolvedImdbStr
                    HydratedMediaMeta(
                        mediaId = finalTmdbStr ?: finalImdbStr ?: cleanRaw ?: title,
                        tmdbId = finalTmdbStr?.toLongOrNull(),
                        imdbId = finalImdbStr,
                        title = cachedEnriched.title.ifBlank { title },
                        year = cachedEnriched.year ?: year,
                        posterUrl = cachedEnriched.posterUrl,
                        backdropUrl = cachedEnriched.backdropUrl ?: cachedEnriched.posterUrl,
                        rating = cachedEnriched.rating?.toDoubleOrNull(),
                        durationMs = cachedEnriched.runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L }
                    )
                } else if (!lookupId.isNullOrBlank()) {
                    fetchLightweightTmdbMeta(lookupId, title, year, isTv)
                } else null

                if (meta != null) {
                    val mergedImdb = meta.imdbId ?: resolvedImdbStr
                    val finalMeta = meta.copy(imdbId = mergedImdb)
                    if (!finalMeta.posterUrl.isNullOrBlank()) {
                        hydratedCache[cacheKey] = finalMeta
                    }
                    return@withPermit finalMeta
                }

                val fallbackId = resolvedTmdbStr ?: resolvedImdbStr ?: cleanRaw ?: title
                HydratedMediaMeta(
                    mediaId = fallbackId,
                    tmdbId = resolvedTmdbStr?.toLongOrNull(),
                    imdbId = resolvedImdbStr,
                    title = title,
                    year = year,
                    posterUrl = null,
                    backdropUrl = null,
                    rating = null,
                    durationMs = null
                )
            } catch (e: Exception) {
                Log.w(TAG, "Metadata hydration fallback for $title: ${e.message}")
                val fallbackId = resolvedTmdbStr ?: resolvedImdbStr ?: cleanRaw ?: title
                HydratedMediaMeta(
                    mediaId = fallbackId,
                    tmdbId = resolvedTmdbStr?.toLongOrNull(),
                    imdbId = resolvedImdbStr,
                    title = title,
                    year = year,
                    posterUrl = null,
                    backdropUrl = null,
                    rating = null,
                    durationMs = null
                )
            }
        }
    }

    /**
     * Background repair job backfilling missing artwork via column-only SQL updates
     * so soft-delete flags and dirty timestamps remain untouched.
     */
    fun repairMissingLocalPostersAsync() {
        scope.launch {
            try {
                SyncCoordinator.dataMutex.withLock {
                    repairMissingLocalPosters(currentProfileId())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error repairing missing local posters", e)
            }
        }
    }

    suspend fun repairMissingLocalPosters(profileId: String = currentProfileId()) = withContext(Dispatchers.IO) {
        if (!posterRepairRunning.compareAndSet(false, true)) return@withContext
        try {
            val watchlists = watchlistDao.getAllWatchlist().filter {
                it.profileId == profileId && (it.posterUrl.isNullOrBlank() || it.backdropUrl.isNullOrBlank())
            }
            for (w in watchlists) {
                val isTv = !w.mediaType.equals("MOVIE", ignoreCase = true)
                val hydrated = hydrateMediaMeta(
                    rawMediaId = w.mediaId,
                    tmdbId = w.mediaId.toLongOrNull(),
                    imdbId = w.mediaId.takeIf { it.startsWith("tt") },
                    title = w.title,
                    year = w.year,
                    isTv = isTv,
                    fetchRemoteIfUncached = true
                )
                if (!hydrated.posterUrl.isNullOrBlank()) {
                    watchlistDao.backfillArtwork(
                        mediaId = w.mediaId,
                        profileId = profileId,
                        posterUrl = hydrated.posterUrl,
                        backdropUrl = hydrated.backdropUrl ?: hydrated.posterUrl,
                        rating = hydrated.rating,
                        year = hydrated.year
                    )
                }
            }

            val histories = playbackHistoryDao.getAllHistory().filter {
                it.profileId == profileId && (it.posterUrl.isNullOrBlank() || it.backdropUrl.isNullOrBlank())
            }
            for (h in histories) {
                val isTv = h.seasonNumber != null || h.episodeNumber != null ||
                    TrackerReconciliationPolicy.isTvMediaType(h.mediaType)
                val hydrated = hydrateMediaMeta(
                    rawMediaId = h.mediaId,
                    tmdbId = h.mediaId.toLongOrNull(),
                    imdbId = h.mediaId.takeIf { it.startsWith("tt") },
                    title = h.title,
                    year = null,
                    isTv = isTv,
                    fetchRemoteIfUncached = true
                )
                if (!hydrated.posterUrl.isNullOrBlank()) {
                    playbackHistoryDao.backfillArtwork(
                        id = h.id,
                        posterUrl = hydrated.posterUrl,
                        backdropUrl = hydrated.backdropUrl ?: hydrated.posterUrl
                    )
                }
            }

            val playlists = playlistRepository.awaitLoaded()
            for (pl in playlists) {
                if (pl.isSystem || pl.isDeleted) continue
                for (item in pl.items) {
                    if (item.posterUrl.isNullOrBlank() || item.backdropUrl.isNullOrBlank()) {
                        val hydrated = hydrateMediaMeta(
                            rawMediaId = item.mediaId,
                            tmdbId = item.mediaId.toLongOrNull(),
                            imdbId = item.mediaId.takeIf { it.startsWith("tt") },
                            title = item.title,
                            year = item.year,
                            isTv = item.isTvSeries,
                            fetchRemoteIfUncached = true
                        )
                        if (!hydrated.posterUrl.isNullOrBlank()) {
                            playlistRepository.backfillItemArtwork(
                                mediaId = item.mediaId,
                                title = item.title,
                                posterUrl = hydrated.posterUrl,
                                backdropUrl = hydrated.backdropUrl,
                                year = hydrated.year
                            )
                        }
                    }
                }
            }
        } finally {
            posterRepairRunning.set(false)
        }
    }

    /**
     * Single-flight Trakt token refresh guarded against concurrent 401 storms and disconnect races.
     */
    suspend fun refreshTraktTokenOnUnauthorized(failedAccessToken: String): String? {
        return traktRefreshMutex.withLock {
            val gen = traktAuthGeneration.get()
            val storedAccess = preferencesRepository.traktAccessToken.first()
            if (storedAccess.isBlank()) return@withLock null
            if (storedAccess != failedAccessToken) return@withLock storedAccess

            val refreshToken = preferencesRepository.traktRefreshToken.first()
            if (refreshToken.isBlank()) return@withLock null

            val refreshed = traktClient.refreshToken(refreshToken).getOrNull() ?: return@withLock null
            if (refreshed.accessToken.isBlank()) return@withLock null
            if (traktAuthGeneration.get() != gen || preferencesRepository.traktAccessToken.first().isBlank()) {
                return@withLock null
            }

            val username = preferencesRepository.traktUsername.first().ifBlank { "Trakt User" }
            val newRefresh = refreshed.refreshToken.ifBlank { refreshToken }
            preferencesRepository.setTraktAuth(refreshed.accessToken, newRefresh, username)
            Log.i(TAG, "Successfully refreshed Trakt OAuth access token")
            refreshed.accessToken
        }
    }

    private suspend fun <T> withTraktTokenRetry(
        initialToken: String,
        block: suspend (String) -> Result<T>
    ): Pair<String, Result<T>> {
        var activeToken = initialToken
        var res = block(activeToken)
        if (res.isFailure && res.exceptionOrNull()?.message?.contains("401") == true) {
            val refreshed = refreshTraktTokenOnUnauthorized(activeToken)
            if (!refreshed.isNullOrBlank()) {
                activeToken = refreshed
                res = block(activeToken)
            }
        }
        return activeToken to res
    }

    suspend fun syncAll(force: Boolean = false): Result<Unit> =
        syncTarget(TrackerSyncTarget.ALL, force)

    suspend fun syncTraktNow(): Result<Unit> =
        syncTarget(TrackerSyncTarget.TRAKT, force = true)

    suspend fun syncSimklNow(): Result<Unit> =
        syncTarget(TrackerSyncTarget.SIMKL, force = true)

    private suspend fun syncTarget(
        target: TrackerSyncTarget,
        force: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isTrackerSyncAllowed()) {
            return@withContext Result.success(Unit)
        }

        if (SyncCoordinator.isGoogleInitialRestoreActive.get()) {
            if (!force) {
                return@withContext Result.success(Unit)
            }
            withTimeoutOrNull(15_000L) {
                while (SyncCoordinator.isGoogleInitialRestoreActive.get()) {
                    delay(200L)
                }
            }
        }

        if (syncMutex.isLocked) {
            enqueuePendingTarget(target)
            return@withContext Result.success(Unit)
        }

        val result = syncMutex.withLock {
            if (!isTrackerSyncAllowed()) {
                return@withLock Result.success(Unit)
            }

            var activeTraktToken = preferencesRepository.traktAccessToken.first()
            val simklToken = preferencesRepository.simklAccessToken.first()
            val traktGen = traktAuthGeneration.get()
            val simklGen = simklAuthGeneration.get()

            val runTrakt = target.includesTrakt() && activeTraktToken.isNotBlank()
            val runSimkl = target.includesSimkl() && simklToken.isNotBlank()

            val syncProfileId = currentProfileId()

            if (!runTrakt && !runSimkl) {
                if (target == TrackerSyncTarget.ALL && activeTraktToken.isBlank() && simklToken.isBlank()) {
                    SyncCoordinator.dataMutex.withLock {
                        repairMissingLocalPosters(syncProfileId)
                    }
                    _syncStatusMessage.value = "No cloud tracking accounts connected"
                }
                return@withLock Result.success(Unit)
            }

            val traktWatchlistEnabled = preferencesRepository.traktSyncWatchlist.first()
            val traktPlaybackEnabled = preferencesRepository.traktSyncPlayback.first()
            val traktHistoryEnabled = preferencesRepository.traktSyncHistory.first()
            val traktListsEnabled = preferencesRepository.traktSyncLists.first()

            val simklWatchlistEnabled = preferencesRepository.simklSyncWatchlist.first()
            val simklHistoryEnabled = preferencesRepository.simklSyncHistory.first()

            _isSyncing.value = true
            _syncError.value = null
            if (runTrakt) {
                _isTraktSyncing.value = true
                _traktSyncError.value = null
                _traktStatusMessage.value = "Syncing with Trakt…"
            }
            if (runSimkl) {
                _isSimklSyncing.value = true
                _simklSyncError.value = null
                _simklStatusMessage.value = "Syncing with Simkl…"
            }
            _syncStatusMessage.value = when (target) {
                TrackerSyncTarget.TRAKT -> "Syncing with Trakt…"
                TrackerSyncTarget.SIMKL -> "Syncing with Simkl…"
                TrackerSyncTarget.ALL -> "Starting media synchronization…"
            }

            val traktErrors = mutableListOf<String>()
            val simklErrors = mutableListOf<String>()
            var didMutateLocal = false

            try {
                SyncCoordinator.dataMutex.withLock {
                    if (runTrakt && traktWatchlistEnabled && isTraktSessionStillValid(traktGen) && preferencesRepository.traktAccessToken.first().isNotBlank()) {
                        _traktStatusMessage.value = "Syncing Trakt watchlist…"
                        _syncStatusMessage.value = "Syncing Trakt Watchlist…"
                        val out = syncTraktWatchlistInternal(activeTraktToken, syncProfileId, traktGen)
                        if (out.activeToken.isNotBlank()) activeTraktToken = out.activeToken
                        out.error?.let { traktErrors.add(it) }
                        if (out.didMutateLocal) didMutateLocal = true
                    }

                    if (runSimkl && simklWatchlistEnabled && isSimklSessionStillValid(simklGen) && preferencesRepository.simklAccessToken.first().isNotBlank()) {
                        _simklStatusMessage.value = "Syncing Simkl watchlist…"
                        _syncStatusMessage.value = "Syncing Simkl Watchlist…"
                        val out = syncSimklWatchlistInternal(simklToken, syncProfileId, simklGen)
                        out.error?.let { simklErrors.add(it) }
                        if (out.didMutateLocal) didMutateLocal = true
                    }

                    if (runTrakt && traktPlaybackEnabled && isTraktSessionStillValid(traktGen) && preferencesRepository.traktAccessToken.first().isNotBlank()) {
                        _traktStatusMessage.value = "Syncing resume points…"
                        _syncStatusMessage.value = "Syncing Continue Watching…"
                        val out = syncTraktPlaybackInternal(activeTraktToken, syncProfileId, traktGen)
                        if (out.activeToken.isNotBlank()) activeTraktToken = out.activeToken
                        out.error?.let { traktErrors.add(it) }
                        if (out.didMutateLocal) didMutateLocal = true
                    }

                    if (runSimkl && (simklWatchlistEnabled || simklHistoryEnabled) && isSimklSessionStillValid(simklGen) && preferencesRepository.simklAccessToken.first().isNotBlank()) {
                        _simklStatusMessage.value = "Syncing watching progress…"
                        _syncStatusMessage.value = "Syncing Simkl Watching Progress…"
                        val out = syncSimklPlaybackInternal(simklToken, syncProfileId, simklGen)
                        out.error?.let { simklErrors.add(it) }
                        if (out.didMutateLocal) didMutateLocal = true
                    }

                    if (runTrakt && traktHistoryEnabled && isTraktSessionStillValid(traktGen) && preferencesRepository.traktAccessToken.first().isNotBlank()) {
                        _traktStatusMessage.value = "Syncing watch history…"
                        _syncStatusMessage.value = "Syncing Trakt Watch History…"
                        val out = syncTraktHistoryInternal(activeTraktToken, syncProfileId, traktGen)
                        if (out.activeToken.isNotBlank()) activeTraktToken = out.activeToken
                        out.error?.let { traktErrors.add(it) }
                        if (out.didMutateLocal) didMutateLocal = true
                    }

                    if (runSimkl && simklHistoryEnabled && isSimklSessionStillValid(simklGen) && preferencesRepository.simklAccessToken.first().isNotBlank()) {
                        _simklStatusMessage.value = "Syncing watch history…"
                        _syncStatusMessage.value = "Syncing Simkl Watch History…"
                        val out = syncSimklHistoryInternal(simklToken, syncProfileId, simklGen)
                        out.error?.let { simklErrors.add(it) }
                        if (out.didMutateLocal) didMutateLocal = true
                    }

                    if (runTrakt && traktListsEnabled && isTraktSessionStillValid(traktGen) && preferencesRepository.traktAccessToken.first().isNotBlank()) {
                        _traktStatusMessage.value = "Syncing custom playlists…"
                        _syncStatusMessage.value = "Syncing Custom Playlists…"
                        val out = syncTraktListsInternal(activeTraktToken, syncProfileId, traktGen)
                        if (out.activeToken.isNotBlank()) activeTraktToken = out.activeToken
                        out.error?.let { traktErrors.add(it) }
                        if (out.didMutateLocal) didMutateLocal = true
                    }

                    repairMissingLocalPosters(syncProfileId)
                }

                // Trigger Google Cloud sync AFTER releasing dataMutex if trackers modified local data
                if (didMutateLocal) {
                    SupabaseSyncManager.getInstance(context).scheduleSync(1500L)
                }

                val timestamp = System.currentTimeMillis()
                var anyTimestampSaved = false

                if (runTrakt && isTraktSessionStillValid(traktGen) && preferencesRepository.traktAccessToken.first().isNotBlank()) {
                    if (traktErrors.isEmpty()) {
                        preferencesRepository.setTraktLastSyncTimestamp(timestamp)
                        anyTimestampSaved = true
                        _traktSyncError.value = null
                        _traktStatusMessage.value = null
                    } else {
                        val firstErr = traktErrors.first()
                        _traktSyncError.value = firstErr
                        _traktStatusMessage.value = "Sync warning: $firstErr"
                    }
                }

                if (runSimkl && isSimklSessionStillValid(simklGen) && preferencesRepository.simklAccessToken.first().isNotBlank()) {
                    if (simklErrors.isEmpty()) {
                        preferencesRepository.setSimklLastSyncTimestamp(timestamp)
                        anyTimestampSaved = true
                        _simklSyncError.value = null
                        _simklStatusMessage.value = null
                    } else {
                        val firstErr = simklErrors.first()
                        _simklSyncError.value = firstErr
                        _simklStatusMessage.value = "Sync warning: $firstErr"
                    }
                }

                if (anyTimestampSaved) {
                    preferencesRepository.setLastMediaSyncTimestamp(timestamp)
                }

                val combinedErrors = traktErrors + simklErrors
                if (combinedErrors.isNotEmpty()) {
                    val combinedErr = combinedErrors.first()
                    _syncError.value = combinedErr
                    _syncStatusMessage.value = "Synced with warnings: $combinedErr"
                    Log.w(TAG, "Media sync completed with warnings: $combinedErrors")
                } else {
                    _syncStatusMessage.value = "Synchronization completed successfully"
                    Log.i(TAG, "Media sync ($target) finished successfully at $timestamp")
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Media sync ($target) failed", e)
                val msg = e.message ?: "Sync encountered an unexpected error"
                if (runTrakt && isTraktSessionStillValid(traktGen)) {
                    _traktSyncError.value = msg
                    _traktStatusMessage.value = "Sync failed: $msg"
                }
                if (runSimkl && isSimklSessionStillValid(simklGen)) {
                    _simklSyncError.value = msg
                    _simklStatusMessage.value = "Sync failed: $msg"
                }
                _syncError.value = msg
                _syncStatusMessage.value = "Sync failed: $msg"
                Result.failure(e)
            } finally {
                _isTraktSyncing.value = false
                _isSimklSyncing.value = false
                _isSyncing.value = false
                scope.launch {
                    delay(3500)
                    if (_syncStatusMessage.value?.startsWith("Sync") == true) {
                        _syncStatusMessage.value = null
                    }
                }
            }
        }

        val queuedTarget = pendingTarget.getAndSet(null)
        if (queuedTarget != null) {
            scope.launch {
                syncTarget(queuedTarget, force = true)
            }
        }

        result
    }

    /* -------------------------------------------------------------------------- */
    /*                               TRAKT WATCHLIST                               */
    /* -------------------------------------------------------------------------- */

    private suspend fun syncTraktWatchlistInternal(
        token: String,
        profileId: String,
        traktGen: Long
    ): SubSyncOutcome {
        val (activeToken, result) = withTraktTokenRetry(token) { t -> traktClient.getWatchlist(t) }
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: "Failed to fetch Trakt watchlist"
            Log.w(TAG, msg)
            return SubSyncOutcome(error = msg, activeToken = activeToken)
        }
        if (!isTraktSessionStillValid(traktGen)) {
            return SubSyncOutcome(activeToken = activeToken)
        }

        val remoteItems = result.getOrNull() ?: emptyList()
        val allLocal = watchlistDao.getAllWatchlistIncludingDeleted(profileId)
        val localByMediaId = allLocal.associateBy { it.mediaId }.toMutableMap()

        var mutated = false
        val removedRemoteKeys = mutableSetOf<String>()
        val removeMovies = mutableListOf<TraktMovie>()
        val removeShows = mutableListOf<TraktShow>()

        for (item in remoteItems) {
            if (!isTraktSessionStillValid(traktGen)) break
            val isMovie = item.type.equals("movie", ignoreCase = true)
            val movie = item.movie
            val show = item.show

            val tmdbId = movie?.ids?.tmdb ?: show?.ids?.tmdb
            val imdbId = movie?.ids?.imdb ?: show?.ids?.imdb
            val title = movie?.title ?: show?.title ?: continue
            val year = movie?.year ?: show?.year

            val hydrated = hydrateMediaMeta(
                rawMediaId = tmdbId?.toString() ?: imdbId,
                tmdbId = tmdbId,
                imdbId = imdbId,
                title = title,
                year = year,
                isTv = !isMovie
            )

            val existing = localByMediaId[hydrated.mediaId]
                ?: imdbId?.let { localByMediaId[it] }
                ?: tmdbId?.toString()?.let { localByMediaId[it] }

            val addedEpoch = parseIsoTimestamp(item.listed_at)
            when (TrackerReconciliationPolicy.decideWatchlistPull(existing, addedEpoch)) {
                TrackerReconciliationPolicy.WatchlistPullAction.INSERT_LOCAL -> {
                    val now = System.currentTimeMillis()
                    val effectiveAdded = addedEpoch ?: now
                    val entity = WatchlistEntity(
                        mediaId = hydrated.mediaId,
                        profileId = profileId,
                        title = hydrated.title,
                        posterUrl = hydrated.posterUrl,
                        backdropUrl = hydrated.backdropUrl,
                        mediaType = if (isMovie) "MOVIE" else "TV_SERIES",
                        rating = hydrated.rating,
                        year = hydrated.year,
                        status = "TO_BE_WATCHED",
                        addedTimestamp = effectiveAdded,
                        isDirty = true,
                        isDeleted = false,
                        clientUpdatedAtMs = maxOf(now, effectiveAdded)
                    )
                    watchlistDao.insert(entity)
                    localByMediaId[hydrated.mediaId] = entity
                    mutated = true
                }
                TrackerReconciliationPolicy.WatchlistPullAction.RESURRECT_REMOTE_NEWER -> {
                    val cur = existing!!
                    val now = System.currentTimeMillis()
                    val nextTs = maxOf(now, maxOf(cur.clientUpdatedAtMs + 1L, addedEpoch ?: 0L))
                    val updated = cur.copy(
                        title = hydrated.title.ifBlank { cur.title },
                        posterUrl = cur.posterUrl ?: hydrated.posterUrl,
                        backdropUrl = cur.backdropUrl ?: hydrated.backdropUrl,
                        rating = cur.rating ?: hydrated.rating,
                        year = cur.year ?: hydrated.year,
                        status = "TO_BE_WATCHED",
                        addedTimestamp = addedEpoch ?: cur.addedTimestamp,
                        isDeleted = false,
                        isDirty = true,
                        clientUpdatedAtMs = nextTs
                    )
                    watchlistDao.insert(updated)
                    localByMediaId[cur.mediaId] = updated
                    mutated = true
                }
                TrackerReconciliationPolicy.WatchlistPullAction.SKIP_LOCAL_TOMBSTONE_AND_REMOVE_REMOTE -> {
                    tmdbId?.let { removedRemoteKeys.add(it.toString()) }
                    imdbId?.let { removedRemoteKeys.add(it) }
                    removedRemoteKeys.add(hydrated.mediaId)
                    val ids = TraktIds(tmdb = tmdbId, imdb = imdbId)
                    if (isMovie) {
                        removeMovies.add(TraktMovie(title = title, year = year, ids = ids))
                    } else {
                        removeShows.add(TraktShow(title = title, year = year, ids = ids))
                    }
                }
                TrackerReconciliationPolicy.WatchlistPullAction.KEEP_LOCAL -> {
                    val cur = existing!!
                    if ((cur.posterUrl.isNullOrBlank() || cur.backdropUrl.isNullOrBlank()) &&
                        !hydrated.posterUrl.isNullOrBlank()
                    ) {
                        watchlistDao.backfillArtwork(
                            mediaId = cur.mediaId,
                            profileId = profileId,
                            posterUrl = hydrated.posterUrl,
                            backdropUrl = hydrated.backdropUrl ?: hydrated.posterUrl,
                            rating = hydrated.rating,
                            year = hydrated.year
                        )
                    }
                }
            }
        }

        if ((removeMovies.isNotEmpty() || removeShows.isNotEmpty()) && isTraktSessionStillValid(traktGen)) {
            traktClient.removeFromWatchlist(
                activeToken,
                TraktSyncItemsPayload(movies = removeMovies, shows = removeShows)
            )
        }

        val remoteTmdbIds = remoteItems.mapNotNull { it.movie?.ids?.tmdb ?: it.show?.ids?.tmdb }
            .map { it.toString() }.filterNot { it in removedRemoteKeys }.toSet()
        val remoteImdbIds = remoteItems.mapNotNull { it.movie?.ids?.imdb ?: it.show?.ids?.imdb }
            .filterNot { it in removedRemoteKeys }.toSet()
        val remoteTitles = remoteItems.filter { item ->
            val t = (item.movie?.ids?.tmdb ?: item.show?.ids?.tmdb)?.toString()
            val im = item.movie?.ids?.imdb ?: item.show?.ids?.imdb
            t !in removedRemoteKeys && im !in removedRemoteKeys
        }.mapNotNull { (it.movie?.title ?: it.show?.title)?.lowercase()?.trim() }.toSet()

        val unsyncedMovies = mutableListOf<TraktMovie>()
        val unsyncedShows = mutableListOf<TraktShow>()

        for (local in allLocal) {
            if (local.isDeleted || local.status.equals("COMPLETED", ignoreCase = true)) continue
            val isMovie = local.mediaType.equals("MOVIE", ignoreCase = true)
            val hydrated = hydrateMediaMeta(
                rawMediaId = local.mediaId,
                tmdbId = local.mediaId.toLongOrNull(),
                imdbId = local.mediaId.takeIf { it.startsWith("tt") },
                title = local.title,
                year = local.year,
                isTv = !isMovie,
                fetchRemoteIfUncached = local.mediaId.toLongOrNull() == null && !local.mediaId.startsWith("tt")
            )

            val tmdbId = hydrated.tmdbId
            val imdbId = hydrated.imdbId

            val alreadyRemote = (tmdbId != null && remoteTmdbIds.contains(tmdbId.toString())) ||
                (imdbId != null && remoteImdbIds.contains(imdbId)) ||
                remoteTitles.contains(local.title.lowercase().trim())

            if (!alreadyRemote) {
                val ids = TraktIds(tmdb = tmdbId, imdb = imdbId)
                if (isMovie) {
                    unsyncedMovies.add(TraktMovie(title = local.title, year = local.year ?: hydrated.year, ids = ids))
                } else {
                    unsyncedShows.add(TraktShow(title = local.title, year = local.year ?: hydrated.year, ids = ids))
                }
            }
        }

        if ((unsyncedMovies.isNotEmpty() || unsyncedShows.isNotEmpty()) && isTraktSessionStillValid(traktGen)) {
            val payload = TraktSyncItemsPayload(movies = unsyncedMovies, shows = unsyncedShows)
            val (_, pushRes) = withTraktTokenRetry(activeToken) { t -> traktClient.addToWatchlist(t, payload) }
            if (pushRes.isFailure) {
                Log.w(TAG, "Failed to push local watchlist to Trakt: ${pushRes.exceptionOrNull()?.message}")
            }
        }
        return SubSyncOutcome(didMutateLocal = mutated, activeToken = activeToken)
    }

    /* -------------------------------------------------------------------------- */
    /*                               SIMKL WATCHLIST                               */
    /* -------------------------------------------------------------------------- */

    private suspend fun syncSimklWatchlistInternal(
        token: String,
        profileId: String,
        simklGen: Long
    ): SubSyncOutcome {
        val result = simklClient.getAllItems(token, "plantowatch")
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: "Failed to fetch Simkl watchlist"
            Log.w(TAG, msg)
            return SubSyncOutcome(error = msg)
        }
        if (!isSimklSessionStillValid(simklGen)) return SubSyncOutcome()

        val parsed = result.getOrNull() ?: return SubSyncOutcome()
        val allLocal = watchlistDao.getAllWatchlistIncludingDeleted(profileId)
        val localByMediaId = allLocal.associateBy { it.mediaId }.toMutableMap()

        var mutated = false
        val removeMovies = mutableListOf<SimklSyncItem>()
        val removeShows = mutableListOf<SimklSyncItem>()
        val removedRemoteKeys = mutableSetOf<String>()

        for (m in parsed.movies) {
            if (!isSimklSessionStillValid(simklGen)) break
            val movie = m.movie ?: continue
            val tmdbId = movie.ids?.tmdb
            val imdbId = movie.ids?.imdb
            val hydrated = hydrateMediaMeta(
                rawMediaId = tmdbId?.toString() ?: imdbId ?: movie.ids?.simkl?.toString(),
                tmdbId = tmdbId,
                imdbId = imdbId,
                title = movie.title,
                year = movie.year,
                isTv = false
            )
            val existing = localByMediaId[hydrated.mediaId]
                ?: imdbId?.let { localByMediaId[it] }
                ?: tmdbId?.toString()?.let { localByMediaId[it] }
            val addedEpoch = parseIsoTimestamp(m.last_watched_at)

            when (TrackerReconciliationPolicy.decideWatchlistPull(existing, addedEpoch)) {
                TrackerReconciliationPolicy.WatchlistPullAction.INSERT_LOCAL -> {
                    val now = System.currentTimeMillis()
                    val effectiveAdded = addedEpoch ?: now
                    val entity = WatchlistEntity(
                        mediaId = hydrated.mediaId,
                        profileId = profileId,
                        title = hydrated.title,
                        posterUrl = hydrated.posterUrl,
                        backdropUrl = hydrated.backdropUrl,
                        mediaType = "MOVIE",
                        rating = hydrated.rating,
                        year = hydrated.year,
                        status = "TO_BE_WATCHED",
                        addedTimestamp = effectiveAdded,
                        isDirty = true,
                        isDeleted = false,
                        clientUpdatedAtMs = maxOf(now, effectiveAdded)
                    )
                    watchlistDao.insert(entity)
                    localByMediaId[hydrated.mediaId] = entity
                    mutated = true
                }
                TrackerReconciliationPolicy.WatchlistPullAction.RESURRECT_REMOTE_NEWER -> {
                    val cur = existing!!
                    val now = System.currentTimeMillis()
                    val nextTs = maxOf(now, maxOf(cur.clientUpdatedAtMs + 1L, addedEpoch ?: 0L))
                    val updated = cur.copy(
                        title = hydrated.title.ifBlank { cur.title },
                        posterUrl = cur.posterUrl ?: hydrated.posterUrl,
                        backdropUrl = cur.backdropUrl ?: hydrated.backdropUrl,
                        rating = cur.rating ?: hydrated.rating,
                        year = cur.year ?: hydrated.year,
                        status = "TO_BE_WATCHED",
                        isDeleted = false,
                        isDirty = true,
                        clientUpdatedAtMs = nextTs
                    )
                    watchlistDao.insert(updated)
                    localByMediaId[cur.mediaId] = updated
                    mutated = true
                }
                TrackerReconciliationPolicy.WatchlistPullAction.SKIP_LOCAL_TOMBSTONE_AND_REMOVE_REMOTE -> {
                    tmdbId?.let { removedRemoteKeys.add(it.toString()) }
                    imdbId?.let { removedRemoteKeys.add(it) }
                    removedRemoteKeys.add(hydrated.mediaId)
                    removeMovies.add(
                        SimklSyncItem(
                            title = movie.title,
                            ids = SimklIds(simkl = movie.ids?.simkl, imdb = imdbId, tmdb = tmdbId)
                        )
                    )
                }
                TrackerReconciliationPolicy.WatchlistPullAction.KEEP_LOCAL -> {
                    val cur = existing!!
                    if ((cur.posterUrl.isNullOrBlank() || cur.backdropUrl.isNullOrBlank()) &&
                        !hydrated.posterUrl.isNullOrBlank()
                    ) {
                        watchlistDao.backfillArtwork(
                            mediaId = cur.mediaId,
                            profileId = profileId,
                            posterUrl = hydrated.posterUrl,
                            backdropUrl = hydrated.backdropUrl ?: hydrated.posterUrl,
                            rating = hydrated.rating,
                            year = hydrated.year
                        )
                    }
                }
            }
        }

        val allShowEntries = parsed.shows.mapNotNull { entry -> entry.show?.let { it to entry.last_watched_at } } +
            parsed.anime.mapNotNull { entry -> entry.anime?.let { it to entry.last_watched_at } }

        for ((show, lastWatchedStr) in allShowEntries) {
            if (!isSimklSessionStillValid(simklGen)) break
            val tmdbId = show.ids?.tmdb
            val imdbId = show.ids?.imdb
            val hydrated = hydrateMediaMeta(
                rawMediaId = tmdbId?.toString() ?: imdbId ?: show.ids?.simkl?.toString(),
                tmdbId = tmdbId,
                imdbId = imdbId,
                title = show.title,
                year = show.year,
                isTv = true
            )
            val existing = localByMediaId[hydrated.mediaId]
                ?: imdbId?.let { localByMediaId[it] }
                ?: tmdbId?.toString()?.let { localByMediaId[it] }
            val addedEpoch = parseIsoTimestamp(lastWatchedStr)

            when (TrackerReconciliationPolicy.decideWatchlistPull(existing, addedEpoch)) {
                TrackerReconciliationPolicy.WatchlistPullAction.INSERT_LOCAL -> {
                    val now = System.currentTimeMillis()
                    val effectiveAdded = addedEpoch ?: now
                    val entity = WatchlistEntity(
                        mediaId = hydrated.mediaId,
                        profileId = profileId,
                        title = hydrated.title,
                        posterUrl = hydrated.posterUrl,
                        backdropUrl = hydrated.backdropUrl,
                        mediaType = "TV_SERIES",
                        rating = hydrated.rating,
                        year = hydrated.year,
                        status = "TO_BE_WATCHED",
                        addedTimestamp = effectiveAdded,
                        isDirty = true,
                        isDeleted = false,
                        clientUpdatedAtMs = maxOf(now, effectiveAdded)
                    )
                    watchlistDao.insert(entity)
                    localByMediaId[hydrated.mediaId] = entity
                    mutated = true
                }
                TrackerReconciliationPolicy.WatchlistPullAction.RESURRECT_REMOTE_NEWER -> {
                    val cur = existing!!
                    val now = System.currentTimeMillis()
                    val nextTs = maxOf(now, maxOf(cur.clientUpdatedAtMs + 1L, addedEpoch ?: 0L))
                    val updated = cur.copy(
                        title = hydrated.title.ifBlank { cur.title },
                        posterUrl = cur.posterUrl ?: hydrated.posterUrl,
                        backdropUrl = cur.backdropUrl ?: hydrated.backdropUrl,
                        rating = cur.rating ?: hydrated.rating,
                        year = cur.year ?: hydrated.year,
                        status = "TO_BE_WATCHED",
                        isDeleted = false,
                        isDirty = true,
                        clientUpdatedAtMs = nextTs
                    )
                    watchlistDao.insert(updated)
                    localByMediaId[cur.mediaId] = updated
                    mutated = true
                }
                TrackerReconciliationPolicy.WatchlistPullAction.SKIP_LOCAL_TOMBSTONE_AND_REMOVE_REMOTE -> {
                    tmdbId?.let { removedRemoteKeys.add(it.toString()) }
                    imdbId?.let { removedRemoteKeys.add(it) }
                    removedRemoteKeys.add(hydrated.mediaId)
                    removeShows.add(
                        SimklSyncItem(
                            title = show.title,
                            ids = SimklIds(simkl = show.ids?.simkl, imdb = imdbId, tmdb = tmdbId)
                        )
                    )
                }
                TrackerReconciliationPolicy.WatchlistPullAction.KEEP_LOCAL -> {
                    val cur = existing!!
                    if ((cur.posterUrl.isNullOrBlank() || cur.backdropUrl.isNullOrBlank()) &&
                        !hydrated.posterUrl.isNullOrBlank()
                    ) {
                        watchlistDao.backfillArtwork(
                            mediaId = cur.mediaId,
                            profileId = profileId,
                            posterUrl = hydrated.posterUrl,
                            backdropUrl = hydrated.backdropUrl ?: hydrated.posterUrl,
                            rating = hydrated.rating,
                            year = hydrated.year
                        )
                    }
                }
            }
        }

        if ((removeMovies.isNotEmpty() || removeShows.isNotEmpty()) && isSimklSessionStillValid(simklGen)) {
            for (movieChunk in removeMovies.chunked(50)) {
                simklClient.removeFromLibrary(token, SimklSyncPayload(movies = movieChunk))
            }
            for (showChunk in removeShows.chunked(50)) {
                simklClient.removeFromLibrary(token, SimklSyncPayload(shows = showChunk))
            }
        }

        val remoteTmdbIds = (
            parsed.movies.mapNotNull { it.movie?.ids?.tmdb } +
                parsed.shows.mapNotNull { it.show?.ids?.tmdb } +
                parsed.anime.mapNotNull { it.anime?.ids?.tmdb }
            ).map { it.toString() }.filterNot { it in removedRemoteKeys }.toSet()
        val remoteImdbIds = (
            parsed.movies.mapNotNull { it.movie?.ids?.imdb } +
                parsed.shows.mapNotNull { it.show?.ids?.imdb } +
                parsed.anime.mapNotNull { it.anime?.ids?.imdb }
            ).filterNot { it in removedRemoteKeys }.toSet()

        val pushMovies = mutableListOf<SimklSyncItem>()
        val pushShows = mutableListOf<SimklSyncItem>()

        for (local in allLocal) {
            if (local.isDeleted || local.status.equals("COMPLETED", ignoreCase = true)) continue
            val isMovie = local.mediaType.equals("MOVIE", ignoreCase = true)
            val tmdbId = local.mediaId.toLongOrNull()
            val imdbId = if (local.mediaId.startsWith("tt")) local.mediaId else null

            val alreadyRemote = (tmdbId != null && remoteTmdbIds.contains(tmdbId.toString())) ||
                (imdbId != null && remoteImdbIds.contains(imdbId))

            if (!alreadyRemote) {
                val toStatus = if (local.status.equals("WATCHING", ignoreCase = true)) "watching" else "plantowatch"
                val ids = SimklIds(tmdb = tmdbId, imdb = imdbId)
                val item = SimklSyncItem(title = local.title, to = toStatus, ids = ids)
                if (isMovie) pushMovies.add(item) else pushShows.add(item)
            }
        }

        if (isSimklSessionStillValid(simklGen)) {
            val maxBatches = maxOf((pushMovies.size + 49) / 50, (pushShows.size + 49) / 50)
            for (batchIndex in 0 until maxBatches) {
                val mSlice = pushMovies.drop(batchIndex * 50).take(50)
                val sSlice = pushShows.drop(batchIndex * 50).take(50)
                if (mSlice.isNotEmpty() || sSlice.isNotEmpty()) {
                    simklClient.addToLibrary(token, SimklSyncPayload(movies = mSlice, shows = sSlice))
                }
            }
        }
        return SubSyncOutcome(didMutateLocal = mutated)
    }

    /* -------------------------------------------------------------------------- */
    /*                  TRAKT PLAYBACK (TWO-WAY CONTINUE WATCHING)                 */
    /* -------------------------------------------------------------------------- */

    private suspend fun syncTraktPlaybackInternal(
        token: String,
        profileId: String,
        traktGen: Long
    ): SubSyncOutcome {
        val (initialToken, result) = withTraktTokenRetry(token) { t -> traktClient.getPlayback(t) }
        var activeToken = initialToken
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: "Failed to fetch Trakt playback progress"
            Log.w(TAG, msg)
            return SubSyncOutcome(error = msg, activeToken = activeToken)
        }
        if (!isTraktSessionStillValid(traktGen)) return SubSyncOutcome(activeToken = activeToken)

        val remoteProgress = result.getOrNull() ?: emptyList()
        val allLocalHistory = playbackHistoryDao.getAllHistoryIncludingDeleted(profileId)

        var mutated = false
        val remoteMatchedKeys = mutableSetOf<String>()

        for (item in remoteProgress) {
            if (!isTraktSessionStillValid(traktGen)) break
            val isEpisode = item.type.equals("episode", ignoreCase = true)
            val progressPercent = item.progress.coerceIn(1f, 99f)
            val pausedEpoch = parseIsoTimestamp(item.paused_at)

            if (isEpisode) {
                val show = item.show ?: continue
                val episode = item.episode ?: continue
                val hydrated = hydrateMediaMeta(
                    rawMediaId = show.ids.tmdb?.toString() ?: show.ids.imdb,
                    tmdbId = show.ids.tmdb,
                    imdbId = show.ids.imdb,
                    title = show.title,
                    year = show.year,
                    isTv = true
                )
                val mediaId = hydrated.mediaId
                val seasonNum = episode.season.coerceAtLeast(1)
                val epNum = episode.number.coerceAtLeast(1)

                val matchingRows = allLocalHistory.filter {
                    (it.mediaId == mediaId || it.mediaId == hydrated.imdbId || it.mediaId == hydrated.tmdbId?.toString()) &&
                        it.seasonNumber == seasonNum && it.episodeNumber == epNum
                }

                when (
                    TrackerReconciliationPolicy.decidePlaybackPull(
                        localRowsForMedia = matchingRows,
                        remoteEventTsMs = pausedEpoch,
                        remoteIsCompleted = false,
                        isSyntheticPlaceholder = false
                    )
                ) {
                    TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED -> {
                        if (item.id > 0L && isTraktSessionStillValid(traktGen)) {
                            traktClient.removePlayback(activeToken, item.id)
                        }
                    }
                    TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_NEWER_OR_COMPLETED -> {
                        remoteMatchedKeys.add("${mediaId}_s${seasonNum}_e${epNum}")
                        hydrated.tmdbId?.let { remoteMatchedKeys.add("${it}_s${seasonNum}_e${epNum}") }
                        hydrated.imdbId?.let { remoteMatchedKeys.add("${it}_s${seasonNum}_e${epNum}") }
                        if (!hydrated.posterUrl.isNullOrBlank()) {
                            for (row in matchingRows.filterNot { it.isDeleted }) {
                                if (row.posterUrl.isNullOrBlank() || row.backdropUrl.isNullOrBlank()) {
                                    playbackHistoryDao.backfillArtwork(
                                        id = row.id,
                                        posterUrl = hydrated.posterUrl,
                                        backdropUrl = hydrated.backdropUrl ?: hydrated.posterUrl
                                    )
                                }
                            }
                        }
                    }
                    TrackerReconciliationPolicy.PlaybackPullAction.APPLY_REMOTE -> {
                        remoteMatchedKeys.add("${mediaId}_s${seasonNum}_e${epNum}")
                        hydrated.tmdbId?.let { remoteMatchedKeys.add("${it}_s${seasonNum}_e${epNum}") }
                        hydrated.imdbId?.let { remoteMatchedKeys.add("${it}_s${seasonNum}_e${epNum}") }

                        val preferred = matchingRows.firstOrNull { !it.isDeleted } ?: matchingRows.firstOrNull()
                        val effectivePaused = pausedEpoch ?: System.currentTimeMillis()
                        val now = System.currentTimeMillis()
                        val durationMs = preferred?.durationMs?.takeIf { it > 60_000L }
                            ?: hydrated.durationMs?.takeIf { it > 60_000L }
                            ?: 2_700_000L
                        val positionMs = ((progressPercent / 100f) * durationMs).toLong().coerceAtLeast(5_000L)
                        val provider = preferred?.provider ?: "tmdb"
                        val entityId = preferred?.id ?: PlaybackHistoryEntity.buildId(provider, mediaId, seasonNum, epNum, profileId)
                        val ts = maxOf(now, maxOf(preferred?.clientUpdatedAtMs ?: 0L, effectivePaused) + 1L)

                        playbackHistoryDao.upsert(
                            PlaybackHistoryEntity(
                                id = entityId,
                                mediaId = preferred?.mediaId ?: mediaId,
                                title = hydrated.title.ifBlank { show.title },
                                episodeTitle = episode.title ?: preferred?.episodeTitle,
                                seasonNumber = seasonNum,
                                episodeNumber = epNum,
                                posterUrl = preferred?.posterUrl ?: hydrated.posterUrl,
                                backdropUrl = preferred?.backdropUrl ?: hydrated.backdropUrl,
                                currentPositionMs = if (preferred != null && !preferred.isDeleted && preferred.lastWatchedTimestamp >= effectivePaused && preferred.currentPositionMs > 1000L) preferred.currentPositionMs else positionMs,
                                durationMs = durationMs,
                                lastWatchedTimestamp = maxOf(preferred?.lastWatchedTimestamp ?: 0L, effectivePaused),
                                isCompleted = false,
                                provider = provider,
                                mediaType = "TV_SERIES",
                                episodeData = preferred?.episodeData,
                                isDismissedFromContinueWatching = false,
                                profileId = profileId,
                                isDirty = true,
                                isDeleted = false,
                                clientUpdatedAtMs = ts
                            )
                        )
                        mutated = true
                    }
                }
            } else {
                val movie = item.movie ?: continue
                val hydrated = hydrateMediaMeta(
                    rawMediaId = movie.ids.tmdb?.toString() ?: movie.ids.imdb,
                    tmdbId = movie.ids.tmdb,
                    imdbId = movie.ids.imdb,
                    title = movie.title,
                    year = movie.year,
                    isTv = false
                )
                val mediaId = hydrated.mediaId
                val matchingRows = allLocalHistory.filter {
                    it.mediaId == mediaId || it.mediaId == hydrated.imdbId || it.mediaId == hydrated.tmdbId?.toString()
                }

                when (
                    TrackerReconciliationPolicy.decidePlaybackPull(
                        localRowsForMedia = matchingRows,
                        remoteEventTsMs = pausedEpoch,
                        remoteIsCompleted = false,
                        isSyntheticPlaceholder = false
                    )
                ) {
                    TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED -> {
                        if (item.id > 0L && isTraktSessionStillValid(traktGen)) {
                            traktClient.removePlayback(activeToken, item.id)
                        }
                    }
                    TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_NEWER_OR_COMPLETED -> {
                        remoteMatchedKeys.add(mediaId)
                        hydrated.tmdbId?.let { remoteMatchedKeys.add(it.toString()) }
                        hydrated.imdbId?.let { remoteMatchedKeys.add(it) }
                        if (!hydrated.posterUrl.isNullOrBlank()) {
                            for (row in matchingRows.filterNot { it.isDeleted }) {
                                if (row.posterUrl.isNullOrBlank() || row.backdropUrl.isNullOrBlank()) {
                                    playbackHistoryDao.backfillArtwork(
                                        id = row.id,
                                        posterUrl = hydrated.posterUrl,
                                        backdropUrl = hydrated.backdropUrl ?: hydrated.posterUrl
                                    )
                                }
                            }
                        }
                    }
                    TrackerReconciliationPolicy.PlaybackPullAction.APPLY_REMOTE -> {
                        remoteMatchedKeys.add(mediaId)
                        hydrated.tmdbId?.let { remoteMatchedKeys.add(it.toString()) }
                        hydrated.imdbId?.let { remoteMatchedKeys.add(it) }

                        val preferred = matchingRows.firstOrNull { !it.isDeleted } ?: matchingRows.firstOrNull()
                        val effectivePaused = pausedEpoch ?: System.currentTimeMillis()
                        val now = System.currentTimeMillis()
                        val durationMs = preferred?.durationMs?.takeIf { it > 60_000L }
                            ?: hydrated.durationMs?.takeIf { it > 60_000L }
                            ?: 6_000_000L
                        val positionMs = ((progressPercent / 100f) * durationMs).toLong().coerceAtLeast(5_000L)
                        val provider = preferred?.provider ?: "tmdb"
                        val entityId = preferred?.id ?: PlaybackHistoryEntity.buildId(provider, mediaId, null, null, profileId)
                        val ts = maxOf(now, maxOf(preferred?.clientUpdatedAtMs ?: 0L, effectivePaused) + 1L)

                        playbackHistoryDao.upsert(
                            PlaybackHistoryEntity(
                                id = entityId,
                                mediaId = preferred?.mediaId ?: mediaId,
                                title = hydrated.title.ifBlank { movie.title },
                                episodeTitle = null,
                                seasonNumber = null,
                                episodeNumber = null,
                                posterUrl = preferred?.posterUrl ?: hydrated.posterUrl,
                                backdropUrl = preferred?.backdropUrl ?: hydrated.backdropUrl,
                                currentPositionMs = if (preferred != null && !preferred.isDeleted && preferred.lastWatchedTimestamp >= effectivePaused && preferred.currentPositionMs > 1000L) preferred.currentPositionMs else positionMs,
                                durationMs = durationMs,
                                lastWatchedTimestamp = maxOf(preferred?.lastWatchedTimestamp ?: 0L, effectivePaused),
                                isCompleted = false,
                                provider = provider,
                                mediaType = "MOVIE",
                                episodeData = preferred?.episodeData,
                                isDismissedFromContinueWatching = false,
                                profileId = profileId,
                                isDirty = true,
                                isDeleted = false,
                                clientUpdatedAtMs = ts
                            )
                        )
                        mutated = true
                    }
                }
            }
        }

        val localContinueWatching = allLocalHistory
            .filter { !it.isDeleted && !it.isCompleted && it.currentPositionMs > 1000L && !it.isDismissedFromContinueWatching }
            .groupBy { it.mediaId }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.lastWatchedTimestamp } }

        for (local in localContinueWatching.take(25)) {
            if (!isTraktSessionStillValid(traktGen)) break
            val isTv = local.seasonNumber != null || local.episodeNumber != null ||
                TrackerReconciliationPolicy.isTvMediaType(local.mediaType)

            val key = if (isTv) {
                "${local.mediaId}_s${local.seasonNumber ?: 1}_e${local.episodeNumber ?: 1}"
            } else {
                local.mediaId
            }

            if (key in remoteMatchedKeys) continue

            val hydrated = hydrateMediaMeta(
                rawMediaId = local.mediaId,
                tmdbId = local.mediaId.toLongOrNull(),
                imdbId = local.mediaId.takeIf { it.startsWith("tt") },
                title = local.title,
                year = null,
                isTv = isTv,
                fetchRemoteIfUncached = local.mediaId.toLongOrNull() == null && !local.mediaId.startsWith("tt")
            )

            val tmdbId = hydrated.tmdbId
            val imdbId = hydrated.imdbId
            if (tmdbId == null && imdbId == null && local.title.isBlank()) continue

            val progressPercent = if (local.durationMs > 0L) {
                ((local.currentPositionMs.toFloat() / local.durationMs.toFloat()) * 100f).coerceIn(1f, 91f)
            } else {
                25f
            }

            val ids = TraktIds(tmdb = tmdbId, imdb = imdbId)
            val scrobbleBody = if (isTv) {
                TraktScrobbleBody(
                    show = TraktShow(title = local.title, year = hydrated.year, ids = ids),
                    episode = TraktEpisode(
                        season = local.seasonNumber ?: 1,
                        number = local.episodeNumber ?: 1,
                        title = local.episodeTitle
                    ),
                    progress = progressPercent
                )
            } else {
                TraktScrobbleBody(
                    movie = TraktMovie(title = local.title, year = hydrated.year, ids = ids),
                    progress = progressPercent
                )
            }
            val (nextTok, _) = withTraktTokenRetry(activeToken) { t ->
                traktClient.scrobble("pause", t, scrobbleBody)
            }
            activeToken = nextTok
        }
        return SubSyncOutcome(didMutateLocal = mutated, activeToken = activeToken)
    }

    /* -------------------------------------------------------------------------- */
    /*                  SIMKL PLAYBACK (TWO-WAY CONTINUE WATCHING)                 */
    /* -------------------------------------------------------------------------- */

    private suspend fun syncSimklPlaybackInternal(
        token: String,
        profileId: String,
        simklGen: Long
    ): SubSyncOutcome {
        val result = simklClient.getAllItems(token, "watching")
        if (result.isFailure) {
            return SubSyncOutcome()
        }
        if (!isSimklSessionStillValid(simklGen)) return SubSyncOutcome()

        val parsed = result.getOrNull() ?: return SubSyncOutcome()
        val allLocalHistory = playbackHistoryDao.getAllHistoryIncludingDeleted(profileId)
        val remoteIds = mutableSetOf<String>()
        var mutated = false

        val watchingShows = parsed.shows.mapNotNull { e -> e.show?.let { it to e.last_watched_at } } +
            parsed.anime.mapNotNull { e -> e.anime?.let { it to e.last_watched_at } }

        for ((show, lastWatchedStr) in watchingShows) {
            if (!isSimklSessionStillValid(simklGen)) break
            val tmdbId = show.ids?.tmdb
            val imdbId = show.ids?.imdb
            val hydrated = hydrateMediaMeta(
                rawMediaId = tmdbId?.toString() ?: imdbId ?: show.ids?.simkl?.toString(),
                tmdbId = tmdbId,
                imdbId = imdbId,
                title = show.title,
                year = show.year,
                isTv = true
            )
            remoteIds.add(hydrated.mediaId)
            tmdbId?.let { remoteIds.add(it.toString()) }
            imdbId?.let { remoteIds.add(it) }

            val matchingRows = allLocalHistory.filter {
                it.mediaId == hydrated.mediaId || it.mediaId == tmdbId?.toString() || it.mediaId == imdbId
            }
            val lastWatchedEpoch = parseIsoTimestamp(lastWatchedStr) ?: (System.currentTimeMillis() - 86_400_000L)

            if (TrackerReconciliationPolicy.decidePlaybackPull(
                    localRowsForMedia = matchingRows,
                    remoteEventTsMs = lastWatchedEpoch,
                    remoteIsCompleted = false,
                    isSyntheticPlaceholder = true
                ) == TrackerReconciliationPolicy.PlaybackPullAction.APPLY_REMOTE
            ) {
                val durationMs = hydrated.durationMs?.takeIf { it > 60_000L } ?: 1_440_000L
                val entityId = PlaybackHistoryEntity.buildId("tmdb", hydrated.mediaId, 1, 1, profileId)
                playbackHistoryDao.upsert(
                    PlaybackHistoryEntity(
                        id = entityId,
                        mediaId = hydrated.mediaId,
                        title = hydrated.title,
                        episodeTitle = "Episode 1",
                        seasonNumber = 1,
                        episodeNumber = 1,
                        posterUrl = hydrated.posterUrl,
                        backdropUrl = hydrated.backdropUrl,
                        currentPositionMs = (durationMs * 0.25f).toLong(),
                        durationMs = durationMs,
                        lastWatchedTimestamp = lastWatchedEpoch,
                        isCompleted = false,
                        provider = "tmdb",
                        mediaType = "TV_SERIES",
                        profileId = profileId,
                        isDirty = true,
                        isDeleted = false,
                        clientUpdatedAtMs = lastWatchedEpoch
                    )
                )
                mutated = true
            }
        }

        for (m in parsed.movies) {
            if (!isSimklSessionStillValid(simklGen)) break
            val movie = m.movie ?: continue
            val tmdbId = movie.ids?.tmdb
            val imdbId = movie.ids?.imdb
            val hydrated = hydrateMediaMeta(
                rawMediaId = tmdbId?.toString() ?: imdbId ?: movie.ids?.simkl?.toString(),
                tmdbId = tmdbId,
                imdbId = imdbId,
                title = movie.title,
                year = movie.year,
                isTv = false
            )
            remoteIds.add(hydrated.mediaId)
            tmdbId?.let { remoteIds.add(it.toString()) }
            imdbId?.let { remoteIds.add(it) }

            val matchingRows = allLocalHistory.filter {
                it.mediaId == hydrated.mediaId || it.mediaId == tmdbId?.toString() || it.mediaId == imdbId
            }
            val lastWatchedEpoch = parseIsoTimestamp(m.last_watched_at) ?: (System.currentTimeMillis() - 86_400_000L)

            if (TrackerReconciliationPolicy.decidePlaybackPull(
                    localRowsForMedia = matchingRows,
                    remoteEventTsMs = lastWatchedEpoch,
                    remoteIsCompleted = false,
                    isSyntheticPlaceholder = true
                ) == TrackerReconciliationPolicy.PlaybackPullAction.APPLY_REMOTE
            ) {
                val durationMs = hydrated.durationMs?.takeIf { it > 60_000L } ?: 6_000_000L
                val entityId = PlaybackHistoryEntity.buildId("tmdb", hydrated.mediaId, null, null, profileId)
                playbackHistoryDao.upsert(
                    PlaybackHistoryEntity(
                        id = entityId,
                        mediaId = hydrated.mediaId,
                        title = hydrated.title,
                        episodeTitle = null,
                        seasonNumber = null,
                        episodeNumber = null,
                        posterUrl = hydrated.posterUrl,
                        backdropUrl = hydrated.backdropUrl,
                        currentPositionMs = (durationMs * 0.25f).toLong(),
                        durationMs = durationMs,
                        lastWatchedTimestamp = lastWatchedEpoch,
                        isCompleted = false,
                        provider = "tmdb",
                        mediaType = "MOVIE",
                        profileId = profileId,
                        isDirty = true,
                        isDeleted = false,
                        clientUpdatedAtMs = lastWatchedEpoch
                    )
                )
                mutated = true
            }
        }

        val localContinueWatching = allLocalHistory
            .filter { !it.isDeleted && !it.isCompleted && it.currentPositionMs > 1000L && !it.isDismissedFromContinueWatching }
            .groupBy { it.mediaId }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.lastWatchedTimestamp } }

        val pushMovies = mutableListOf<SimklSyncItem>()
        val pushShows = mutableListOf<SimklSyncItem>()

        for (local in localContinueWatching.take(30)) {
            if (local.mediaId in remoteIds) continue
            val isTv = local.seasonNumber != null || local.episodeNumber != null ||
                TrackerReconciliationPolicy.isTvMediaType(local.mediaType)

            val tmdbId = local.mediaId.toLongOrNull()
            val imdbId = local.mediaId.takeIf { it.startsWith("tt") }
            val ids = SimklIds(tmdb = tmdbId, imdb = imdbId)
            val syncItem = SimklSyncItem(title = local.title, to = "watching", ids = ids)
            if (isTv) pushShows.add(syncItem) else pushMovies.add(syncItem)
        }

        if ((pushMovies.isNotEmpty() || pushShows.isNotEmpty()) && isSimklSessionStillValid(simklGen)) {
            simklClient.addToLibrary(token, SimklSyncPayload(movies = pushMovies, shows = pushShows))
        }
        return SubSyncOutcome(didMutateLocal = mutated)
    }

    /* -------------------------------------------------------------------------- */
    /*                     TRAKT WATCH HISTORY (TWO-WAY COMPLETED)                 */
    /* -------------------------------------------------------------------------- */

    private suspend fun syncTraktHistoryInternal(
        token: String,
        profileId: String,
        traktGen: Long
    ): SubSyncOutcome {
        val (initialToken, result) = withTraktTokenRetry(token) { t ->
            traktClient.getHistory(t, page = 1, limit = 100)
        }
        var activeToken = initialToken
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: "Failed to fetch Trakt history"
            Log.w(TAG, msg)
            return SubSyncOutcome(error = msg, activeToken = activeToken)
        }
        if (!isTraktSessionStillValid(traktGen)) return SubSyncOutcome(activeToken = activeToken)

        val historyItems = result.getOrNull() ?: emptyList()
        val allLocalHistory = playbackHistoryDao.getAllHistoryIncludingDeleted(profileId)
        val allLocalWatchlist = watchlistDao.getAllWatchlistIncludingDeleted(profileId)
        val localWatchlistMap = allLocalWatchlist.associateBy { it.mediaId }

        val remoteCompletedMediaIds = mutableSetOf<String>()
        val remoteCompletedEpisodeKeys = mutableSetOf<String>()
        var mutated = false
        var hydratedCount = 0
        val seenRemoteKeys = mutableSetOf<String>()

        for (item in historyItems) {
            if (!isTraktSessionStillValid(traktGen)) break
            val isMovie = item.type.equals("movie", ignoreCase = true)
            val movie = item.movie
            val show = item.show
            val episode = item.episode

            val rawTmdb = if (isMovie) movie?.ids?.tmdb else show?.ids?.tmdb
            val rawImdb = if (isMovie) movie?.ids?.imdb else show?.ids?.imdb
            val title = (if (isMovie) movie?.title else show?.title) ?: continue
            val year = if (isMovie) movie?.year else show?.year

            val baseId = rawTmdb?.toString() ?: rawImdb ?: continue
            remoteCompletedMediaIds.add(baseId)
            rawImdb?.let { remoteCompletedMediaIds.add(it) }

            val seasonNum = if (!isMovie) episode?.season else null
            val epNum = if (!isMovie) episode?.number else null
            val uniqueHistKey = if (!isMovie && seasonNum != null && epNum != null) {
                "${baseId}_s${seasonNum}_e${epNum}"
            } else {
                baseId
            }
            if (!seenRemoteKeys.add(uniqueHistKey)) continue

            val shouldHydrateRemote = hydratedCount < 80
            val hydrated = hydrateMediaMeta(
                rawMediaId = baseId,
                tmdbId = rawTmdb,
                imdbId = rawImdb,
                title = title,
                year = year,
                isTv = !isMovie,
                fetchRemoteIfUncached = shouldHydrateRemote
            )
            if (shouldHydrateRemote) hydratedCount++

            val mediaId = hydrated.mediaId
            remoteCompletedMediaIds.add(mediaId)
            if (!isMovie && seasonNum != null && epNum != null) {
                remoteCompletedEpisodeKeys.add("${mediaId}_s${seasonNum}_e${epNum}")
                remoteCompletedEpisodeKeys.add("${baseId}_s${seasonNum}_e${epNum}")
                rawImdb?.let { remoteCompletedEpisodeKeys.add("${it}_s${seasonNum}_e${epNum}") }
            }

            val activeWl = localWatchlistMap[mediaId]?.takeIf { !it.isDeleted }
                ?: localWatchlistMap[baseId]?.takeIf { !it.isDeleted }
            if (activeWl != null) {
                if (isMovie && !activeWl.status.equals("COMPLETED", ignoreCase = true)) {
                    watchlistDao.updateStatus(activeWl.mediaId, "COMPLETED", profileId)
                    mutated = true
                } else if (!isMovie && activeWl.status.equals("TO_BE_WATCHED", ignoreCase = true)) {
                    watchlistDao.updateStatus(activeWl.mediaId, "WATCHING", profileId)
                    mutated = true
                }
            }

            val matchingRows = allLocalHistory.filter {
                (it.mediaId == mediaId || it.mediaId == baseId || it.mediaId == rawImdb) &&
                    it.seasonNumber == seasonNum && it.episodeNumber == epNum
            }
            val watchedEpoch = parseIsoTimestamp(item.watched_at)

            when (
                TrackerReconciliationPolicy.decidePlaybackPull(
                    localRowsForMedia = matchingRows,
                    remoteEventTsMs = watchedEpoch,
                    remoteIsCompleted = true,
                    isSyntheticPlaceholder = false
                )
            ) {
                TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED -> Unit
                TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_NEWER_OR_COMPLETED -> {
                    if (!hydrated.posterUrl.isNullOrBlank()) {
                        for (row in matchingRows.filterNot { it.isDeleted }) {
                            if (row.posterUrl.isNullOrBlank() || row.backdropUrl.isNullOrBlank()) {
                                playbackHistoryDao.backfillArtwork(
                                    id = row.id,
                                    posterUrl = hydrated.posterUrl,
                                    backdropUrl = hydrated.backdropUrl ?: hydrated.posterUrl
                                )
                            }
                        }
                    }
                }
                TrackerReconciliationPolicy.PlaybackPullAction.APPLY_REMOTE -> {
                    val existingHist = matchingRows.firstOrNull { !it.isDeleted } ?: matchingRows.firstOrNull()
                    val resolvedPoster = existingHist?.posterUrl?.takeIf { it.isNotBlank() } ?: hydrated.posterUrl
                    val resolvedBackdrop = existingHist?.backdropUrl?.takeIf { it.isNotBlank() }
                        ?: hydrated.backdropUrl ?: resolvedPoster
                    if (existingHist != null || !resolvedPoster.isNullOrBlank()) {
                        val effectiveWatched = watchedEpoch ?: System.currentTimeMillis()
                        val durationMs = existingHist?.durationMs?.takeIf { it > 60_000L }
                            ?: hydrated.durationMs?.takeIf { it > 60_000L }
                            ?: if (isMovie) 6_000_000L else 2_700_000L
                        val provider = existingHist?.provider ?: "tmdb"
                        val entityId = existingHist?.id ?: PlaybackHistoryEntity.buildId(provider, mediaId, seasonNum, epNum, profileId)
                        val now = System.currentTimeMillis()
                        val ts = maxOf(now, maxOf(existingHist?.clientUpdatedAtMs ?: 0L, effectiveWatched) + 1L)

                        playbackHistoryDao.upsert(
                            PlaybackHistoryEntity(
                                id = entityId,
                                mediaId = existingHist?.mediaId ?: mediaId,
                                title = hydrated.title.ifBlank { title },
                                episodeTitle = episode?.title ?: existingHist?.episodeTitle,
                                seasonNumber = seasonNum,
                                episodeNumber = epNum,
                                posterUrl = resolvedPoster,
                                backdropUrl = resolvedBackdrop,
                                currentPositionMs = durationMs,
                                durationMs = durationMs,
                                lastWatchedTimestamp = maxOf(existingHist?.lastWatchedTimestamp ?: 0L, effectiveWatched),
                                isCompleted = true,
                                provider = provider,
                                mediaType = if (isMovie) "MOVIE" else "TV_SERIES",
                                episodeData = existingHist?.episodeData,
                                isDismissedFromContinueWatching = true,
                                profileId = profileId,
                                isDirty = true,
                                isDeleted = false,
                                clientUpdatedAtMs = ts
                            )
                        )
                        mutated = true
                    }
                }
            }
        }

        val unsyncedMovies = mutableListOf<TraktMovie>()
        val pushedMovieKeys = mutableSetOf<String>()
        val episodeBuckets = linkedMapOf<String, Pair<TraktShow, MutableMap<Int, MutableSet<Int>>>>()
        val wholeShowTargets = mutableListOf<TraktHistoryShow>()

        for (local in allLocalHistory.filter { !it.isDeleted && it.isCompleted }) {
            val isTv = local.seasonNumber != null || local.episodeNumber != null ||
                TrackerReconciliationPolicy.isTvMediaType(local.mediaType)
            val tmdbId = local.mediaId.toLongOrNull()
            val imdbId = local.mediaId.takeIf { it.startsWith("tt") }
            val ids = TraktIds(tmdb = tmdbId, imdb = imdbId)

            if (isTv && local.seasonNumber != null && local.episodeNumber != null) {
                val epKey = "${local.mediaId}_s${local.seasonNumber}_e${local.episodeNumber}"
                if (epKey in remoteCompletedEpisodeKeys) continue
                val showKey = tmdbId?.toString() ?: imdbId ?: local.title.trim().lowercase()
                val bucket = episodeBuckets.getOrPut(showKey) {
                    TraktShow(title = local.title, ids = ids) to linkedMapOf()
                }
                bucket.second.getOrPut(local.seasonNumber) { linkedSetOf() }.add(local.episodeNumber)
            } else if (!isTv) {
                if (local.mediaId !in remoteCompletedMediaIds && pushedMovieKeys.add(local.mediaId)) {
                    unsyncedMovies.add(TraktMovie(title = local.title, ids = ids))
                }
            }
        }

        val completedWatchlist = allLocalWatchlist.filter {
            !it.isDeleted && it.status.equals("COMPLETED", ignoreCase = true)
        }
        for (local in completedWatchlist) {
            val isMovie = local.mediaType.equals("MOVIE", ignoreCase = true)
            val tmdbId = local.mediaId.toLongOrNull()
            val imdbId = local.mediaId.takeIf { it.startsWith("tt") }
            val ids = TraktIds(tmdb = tmdbId, imdb = imdbId)
            if (isMovie) {
                if (local.mediaId !in remoteCompletedMediaIds && pushedMovieKeys.add(local.mediaId)) {
                    unsyncedMovies.add(TraktMovie(title = local.title, year = local.year, ids = ids))
                }
            } else {
                val showKey = tmdbId?.toString() ?: imdbId ?: local.title.trim().lowercase()
                if (local.mediaId !in remoteCompletedMediaIds && !episodeBuckets.containsKey(showKey)) {
                    wholeShowTargets.add(TraktHistoryShow(title = local.title, year = local.year, ids = ids, seasons = null))
                }
            }
        }

        val historyShows = episodeBuckets.values.map { (show, seasonMap) ->
            TraktHistoryShow(
                title = show.title,
                year = show.year,
                ids = show.ids,
                seasons = seasonMap.entries.map { (sNum, epNums) ->
                    TraktSyncSeason(
                        number = sNum,
                        episodes = epNums.sorted().map { TraktSyncEpisode(number = it) }
                    )
                }
            )
        } + wholeShowTargets

        if ((unsyncedMovies.isNotEmpty() || historyShows.isNotEmpty()) && isTraktSessionStillValid(traktGen)) {
            val (nextTok, _) = withTraktTokenRetry(activeToken) { t ->
                traktClient.addHistoryItems(t, TraktHistoryPayload(movies = unsyncedMovies, shows = historyShows))
            }
            activeToken = nextTok
        }
        return SubSyncOutcome(didMutateLocal = mutated, activeToken = activeToken)
    }

    /* -------------------------------------------------------------------------- */
    /*                     SIMKL WATCH HISTORY (TWO-WAY COMPLETED)                 */
    /* -------------------------------------------------------------------------- */

    private suspend fun syncSimklHistoryInternal(
        token: String,
        profileId: String,
        simklGen: Long
    ): SubSyncOutcome {
        val result = simklClient.getAllItems(token, "completed")
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message ?: "Failed to fetch Simkl history"
            Log.w(TAG, msg)
            return SubSyncOutcome(error = msg)
        }
        if (!isSimklSessionStillValid(simklGen)) return SubSyncOutcome()

        val parsed = result.getOrNull() ?: return SubSyncOutcome()
        val allLocalHistory = playbackHistoryDao.getAllHistoryIncludingDeleted(profileId)
        val allLocalWatchlist = watchlistDao.getAllWatchlistIncludingDeleted(profileId)
        val localWatchlistMap = allLocalWatchlist.associateBy { it.mediaId }

        val remoteCompletedIds = mutableSetOf<String>()
        var mutated = false
        var hydratedCount = 0

        for (m in parsed.movies) {
            if (!isSimklSessionStillValid(simklGen)) break
            val movie = m.movie ?: continue
            val baseId = movie.ids?.tmdb?.toString() ?: movie.ids?.imdb ?: continue
            remoteCompletedIds.add(baseId)
            movie.ids?.imdb?.let { remoteCompletedIds.add(it) }

            val shouldHydrate = hydratedCount < 80
            val hydrated = hydrateMediaMeta(
                rawMediaId = baseId,
                tmdbId = movie.ids?.tmdb,
                imdbId = movie.ids?.imdb,
                title = movie.title,
                year = movie.year,
                isTv = false,
                fetchRemoteIfUncached = shouldHydrate
            )
            if (shouldHydrate) hydratedCount++
            remoteCompletedIds.add(hydrated.mediaId)

            val activeWl = localWatchlistMap[hydrated.mediaId]?.takeIf { !it.isDeleted }
                ?: localWatchlistMap[baseId]?.takeIf { !it.isDeleted }
            if (activeWl != null && !activeWl.status.equals("COMPLETED", ignoreCase = true)) {
                watchlistDao.updateStatus(activeWl.mediaId, "COMPLETED", profileId)
                mutated = true
            }

            val matchingRows = allLocalHistory.filter {
                it.mediaId == hydrated.mediaId || it.mediaId == baseId || it.mediaId == movie.ids?.imdb
            }
            val watchedEpoch = parseIsoTimestamp(m.last_watched_at)

            when (
                TrackerReconciliationPolicy.decidePlaybackPull(
                    localRowsForMedia = matchingRows,
                    remoteEventTsMs = watchedEpoch,
                    remoteIsCompleted = true,
                    isSyntheticPlaceholder = false
                )
            ) {
                TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED -> Unit
                TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_NEWER_OR_COMPLETED -> {
                    if (!hydrated.posterUrl.isNullOrBlank()) {
                        for (row in matchingRows.filterNot { it.isDeleted }) {
                            if (row.posterUrl.isNullOrBlank() || row.backdropUrl.isNullOrBlank()) {
                                playbackHistoryDao.backfillArtwork(
                                    id = row.id,
                                    posterUrl = hydrated.posterUrl,
                                    backdropUrl = hydrated.backdropUrl ?: hydrated.posterUrl
                                )
                            }
                        }
                    }
                }
                TrackerReconciliationPolicy.PlaybackPullAction.APPLY_REMOTE -> {
                    val existingHist = matchingRows.firstOrNull { !it.isDeleted } ?: matchingRows.firstOrNull()
                    val resolvedPoster = existingHist?.posterUrl?.takeIf { it.isNotBlank() } ?: hydrated.posterUrl
                    val resolvedBackdrop = existingHist?.backdropUrl?.takeIf { it.isNotBlank() }
                        ?: hydrated.backdropUrl ?: resolvedPoster
                    if (existingHist != null || !resolvedPoster.isNullOrBlank()) {
                        val effectiveWatched = watchedEpoch ?: System.currentTimeMillis()
                        val durationMs = existingHist?.durationMs?.takeIf { it > 60_000L } ?: hydrated.durationMs ?: 6_000_000L
                        val entityId = existingHist?.id ?: PlaybackHistoryEntity.buildId("tmdb", hydrated.mediaId, null, null, profileId)
                        val now = System.currentTimeMillis()
                        val ts = maxOf(now, maxOf(existingHist?.clientUpdatedAtMs ?: 0L, effectiveWatched) + 1L)

                        playbackHistoryDao.upsert(
                            PlaybackHistoryEntity(
                                id = entityId,
                                mediaId = existingHist?.mediaId ?: hydrated.mediaId,
                                title = hydrated.title,
                                episodeTitle = null,
                                seasonNumber = null,
                                episodeNumber = null,
                                posterUrl = resolvedPoster,
                                backdropUrl = resolvedBackdrop,
                                currentPositionMs = durationMs,
                                durationMs = durationMs,
                                lastWatchedTimestamp = maxOf(existingHist?.lastWatchedTimestamp ?: 0L, effectiveWatched),
                                isCompleted = true,
                                provider = existingHist?.provider ?: "tmdb",
                                mediaType = "MOVIE",
                                isDismissedFromContinueWatching = true,
                                profileId = profileId,
                                isDirty = true,
                                isDeleted = false,
                                clientUpdatedAtMs = ts
                            )
                        )
                        mutated = true
                    }
                }
            }
        }

        val allShowEntries = parsed.shows.mapNotNull { e -> e.show?.let { it to e.last_watched_at } } +
            parsed.anime.mapNotNull { e -> e.anime?.let { it to e.last_watched_at } }

        for ((show, lastWatchedStr) in allShowEntries) {
            if (!isSimklSessionStillValid(simklGen)) break
            val baseId = show.ids?.tmdb?.toString() ?: show.ids?.imdb ?: continue
            remoteCompletedIds.add(baseId)
            show.ids?.imdb?.let { remoteCompletedIds.add(it) }

            val shouldHydrate = hydratedCount < 80
            val hydrated = hydrateMediaMeta(
                rawMediaId = baseId,
                tmdbId = show.ids?.tmdb,
                imdbId = show.ids?.imdb,
                title = show.title,
                year = show.year,
                isTv = true,
                fetchRemoteIfUncached = shouldHydrate
            )
            if (shouldHydrate) hydratedCount++
            remoteCompletedIds.add(hydrated.mediaId)

            val activeWl = localWatchlistMap[hydrated.mediaId]?.takeIf { !it.isDeleted }
                ?: localWatchlistMap[baseId]?.takeIf { !it.isDeleted }
            if (activeWl != null && !activeWl.status.equals("COMPLETED", ignoreCase = true)) {
                watchlistDao.updateStatus(activeWl.mediaId, "COMPLETED", profileId)
                mutated = true
            }

            val matchingRows = allLocalHistory.filter {
                it.mediaId == hydrated.mediaId || it.mediaId == baseId || it.mediaId == show.ids?.imdb
            }
            val watchedEpoch = parseIsoTimestamp(lastWatchedStr)

            when (
                TrackerReconciliationPolicy.decidePlaybackPull(
                    localRowsForMedia = matchingRows,
                    remoteEventTsMs = watchedEpoch,
                    remoteIsCompleted = true,
                    isSyntheticPlaceholder = false
                )
            ) {
                TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_DELETED_OR_DISMISSED -> Unit
                TrackerReconciliationPolicy.PlaybackPullAction.SKIP_LOCAL_NEWER_OR_COMPLETED -> {
                    if (!hydrated.posterUrl.isNullOrBlank()) {
                        for (row in matchingRows.filterNot { it.isDeleted }) {
                            if (row.posterUrl.isNullOrBlank() || row.backdropUrl.isNullOrBlank()) {
                                playbackHistoryDao.backfillArtwork(
                                    id = row.id,
                                    posterUrl = hydrated.posterUrl,
                                    backdropUrl = hydrated.backdropUrl ?: hydrated.posterUrl
                                )
                            }
                        }
                    }
                }
                TrackerReconciliationPolicy.PlaybackPullAction.APPLY_REMOTE -> {
                    val existingHist = matchingRows.firstOrNull { !it.isDeleted } ?: matchingRows.firstOrNull()
                    val resolvedPoster = existingHist?.posterUrl?.takeIf { it.isNotBlank() } ?: hydrated.posterUrl
                    val resolvedBackdrop = existingHist?.backdropUrl?.takeIf { it.isNotBlank() }
                        ?: hydrated.backdropUrl ?: resolvedPoster
                    if (existingHist != null || !resolvedPoster.isNullOrBlank()) {
                        val effectiveWatched = watchedEpoch ?: System.currentTimeMillis()
                        val durationMs = existingHist?.durationMs?.takeIf { it > 60_000L } ?: hydrated.durationMs ?: 2_700_000L
                        val entityId = existingHist?.id ?: PlaybackHistoryEntity.buildId("tmdb", hydrated.mediaId, 1, 1, profileId)
                        val now = System.currentTimeMillis()
                        val ts = maxOf(now, maxOf(existingHist?.clientUpdatedAtMs ?: 0L, effectiveWatched) + 1L)

                        playbackHistoryDao.upsert(
                            PlaybackHistoryEntity(
                                id = entityId,
                                mediaId = existingHist?.mediaId ?: hydrated.mediaId,
                                title = hydrated.title,
                                episodeTitle = existingHist?.episodeTitle,
                                seasonNumber = existingHist?.seasonNumber ?: 1,
                                episodeNumber = existingHist?.episodeNumber ?: 1,
                                posterUrl = resolvedPoster,
                                backdropUrl = resolvedBackdrop,
                                currentPositionMs = durationMs,
                                durationMs = durationMs,
                                lastWatchedTimestamp = maxOf(existingHist?.lastWatchedTimestamp ?: 0L, effectiveWatched),
                                isCompleted = true,
                                provider = existingHist?.provider ?: "tmdb",
                                mediaType = "TV_SERIES",
                                isDismissedFromContinueWatching = true,
                                profileId = profileId,
                                isDirty = true,
                                isDeleted = false,
                                clientUpdatedAtMs = ts
                            )
                        )
                        mutated = true
                    }
                }
            }
        }

        // Local -> Remote: Episode-granular history via /sync/history for TV episodes,
        // whole-show completion ONLY when Watchlist explicitly has status == COMPLETED.
        val pushMovies = mutableListOf<SimklSyncItem>()
        val pushedMovieIds = mutableSetOf<String>()
        val episodeBuckets = linkedMapOf<String, Pair<Pair<String, SimklIds>, MutableMap<Int, MutableSet<Int>>>>()
        val wholeCompletedShows = mutableListOf<SimklSyncItem>()

        for (local in allLocalHistory.filter { !it.isDeleted && it.isCompleted }) {
            val isTv = local.seasonNumber != null || local.episodeNumber != null ||
                TrackerReconciliationPolicy.isTvMediaType(local.mediaType)
            val ids = SimklIds(tmdb = local.mediaId.toLongOrNull(), imdb = local.mediaId.takeIf { it.startsWith("tt") })
            if (isTv && local.seasonNumber != null && local.episodeNumber != null) {
                if (local.mediaId in remoteCompletedIds) continue
                val key = local.mediaId
                val bucket = episodeBuckets.getOrPut(key) {
                    (local.title to ids) to linkedMapOf()
                }
                bucket.second.getOrPut(local.seasonNumber) { linkedSetOf() }.add(local.episodeNumber)
            } else if (!isTv && local.mediaId !in remoteCompletedIds && pushedMovieIds.add(local.mediaId)) {
                pushMovies.add(SimklSyncItem(title = local.title, to = "completed", ids = ids))
            }
        }

        for (local in allLocalWatchlist.filter { !it.isDeleted && it.status.equals("COMPLETED", ignoreCase = true) }) {
            if (local.mediaId in remoteCompletedIds) continue
            val isMovie = local.mediaType.equals("MOVIE", ignoreCase = true)
            val ids = SimklIds(tmdb = local.mediaId.toLongOrNull(), imdb = local.mediaId.takeIf { it.startsWith("tt") })
            if (isMovie) {
                if (pushedMovieIds.add(local.mediaId)) {
                    pushMovies.add(SimklSyncItem(title = local.title, to = "completed", ids = ids))
                }
            } else {
                wholeCompletedShows.add(SimklSyncItem(title = local.title, to = "completed", ids = ids))
            }
        }

        val episodeShows = episodeBuckets.values.map { (meta, seasonMap) ->
            SimklHistoryShow(
                title = meta.first,
                ids = meta.second,
                seasons = seasonMap.entries.map { (sNum, eps) ->
                    SimklHistorySeason(
                        number = sNum,
                        episodes = eps.sorted().map { SimklHistoryEpisode(number = it) }
                    )
                }
            )
        }

        if (isSimklSessionStillValid(simklGen)) {
            for (chunk in episodeShows.chunked(25)) {
                simklClient.addHistoryItems(token, SimklHistoryPayload(shows = chunk))
            }
            val maxLibraryBatches = maxOf((pushMovies.size + 49) / 50, (wholeCompletedShows.size + 49) / 50)
            for (i in 0 until maxLibraryBatches) {
                val mSlice = pushMovies.drop(i * 50).take(50)
                val sSlice = wholeCompletedShows.drop(i * 50).take(50)
                if (mSlice.isNotEmpty() || sSlice.isNotEmpty()) {
                    simklClient.addToLibrary(token, SimklSyncPayload(movies = mSlice, shows = sSlice))
                }
            }
        }
        return SubSyncOutcome(didMutateLocal = mutated)
    }

    /* -------------------------------------------------------------------------- */
    /*                  TRAKT CUSTOM LISTS & PLAYLISTS (TWO-WAY)                   */
    /* -------------------------------------------------------------------------- */

    private suspend fun syncTraktListsInternal(
        token: String,
        profileId: String,
        traktGen: Long
    ): SubSyncOutcome {
        playlistRepository.awaitLoaded()
        val allLocalIncludingDeleted = playlistRepository.getAllPlaylistsIncludingDeleted()
        val deletedTitles = allLocalIncludingDeleted
            .filter { it.isDeleted }
            .map { it.title.trim().lowercase() }
            .toSet()

        val (activeToken1, listsResult) = withTraktTokenRetry(token) { t -> traktClient.getUserLists(t) }
        var activeToken = activeToken1
        if (listsResult.isFailure) {
            val msg = listsResult.exceptionOrNull()?.message ?: "Failed to fetch Trakt custom lists"
            Log.w(TAG, msg)
            return SubSyncOutcome(error = msg, activeToken = activeToken)
        }
        if (!isTraktSessionStillValid(traktGen)) return SubSyncOutcome(activeToken = activeToken)

        var mutated = false
        val remoteLists = (listsResult.getOrNull() ?: emptyList()).toMutableList()

        for (list in remoteLists) {
            if (!isTraktSessionStillValid(traktGen)) break
            if (list.ids.slug.isBlank()) continue
            if (list.name.trim().lowercase() in deletedTitles) continue

            val (nextTok, itemsResult) = withTraktTokenRetry(activeToken) { t ->
                traktClient.getListItems(t, list.ids.slug)
            }
            activeToken = nextTok
            if (itemsResult.isFailure) continue

            val items = itemsResult.getOrNull() ?: emptyList()
            val playlistItems = items.mapNotNull { traktItem ->
                val isMovie = traktItem.type.equals("movie", ignoreCase = true)
                val movie = traktItem.movie
                val show = traktItem.show

                val tmdbId = movie?.ids?.tmdb ?: show?.ids?.tmdb
                val imdbId = movie?.ids?.imdb ?: show?.ids?.imdb
                val title = movie?.title ?: show?.title ?: return@mapNotNull null
                val year = movie?.year ?: show?.year

                val hydrated = hydrateMediaMeta(
                    rawMediaId = tmdbId?.toString() ?: imdbId,
                    tmdbId = tmdbId,
                    imdbId = imdbId,
                    title = title,
                    year = year,
                    isTv = !isMovie
                )

                PlaylistItem(
                    mediaId = hydrated.mediaId,
                    title = hydrated.title,
                    posterUrl = hydrated.posterUrl,
                    backdropUrl = hydrated.backdropUrl,
                    isTvSeries = !isMovie,
                    year = hydrated.year,
                    provider = "tmdb"
                )
            }

            if (playlistItems.isNotEmpty()) {
                if (list.name.equals("Favorites", ignoreCase = true)) {
                    val currentFavs = preferencesRepository.favoriteMediaIds.first()
                    for (pi in playlistItems) {
                        if (pi.mediaId !in currentFavs) {
                            preferencesRepository.toggleFavorite(pi.mediaId)
                            mutated = true
                        }
                        val rawWatchlist = watchlistDao.getRawItem(pi.mediaId, profileId)
                        if (rawWatchlist == null) {
                            val now = System.currentTimeMillis()
                            watchlistDao.insert(
                                WatchlistEntity(
                                    mediaId = pi.mediaId,
                                    profileId = profileId,
                                    title = pi.title,
                                    posterUrl = pi.posterUrl,
                                    backdropUrl = pi.backdropUrl,
                                    mediaType = if (pi.isTvSeries) "TV_SERIES" else "MOVIE",
                                    rating = null,
                                    year = pi.year,
                                    status = "TO_BE_WATCHED",
                                    isDirty = true,
                                    isDeleted = false,
                                    clientUpdatedAtMs = now
                                )
                            )
                            mutated = true
                        }
                    }
                }

                val currentPlaylists = playlistRepository.userPlaylists.value
                val existing = currentPlaylists.find { !it.isDeleted && it.title.equals(list.name, ignoreCase = true) }
                if (existing == null) {
                    playlistRepository.createPlaylist(list.name, playlistItems, syncToRemote = false)
                    mutated = true
                } else {
                    val existingKeys = existing.items.map { it.uniqueKey }.toSet()
                    for (pi in playlistItems) {
                        if (pi.uniqueKey !in existingKeys) {
                            playlistRepository.addItemToPlaylist(existing.id, pi, syncToRemote = false)
                            mutated = true
                        }
                    }
                }
            }
        }

        val updatedLocalPlaylists = playlistRepository.getUserPlaylists().toMutableList()

        val favoriteIds = preferencesRepository.favoriteMediaIds.first()
        if (favoriteIds.isNotEmpty() && updatedLocalPlaylists.none { it.title.equals("Favorites", ignoreCase = true) }) {
            val localWatchlist = watchlistDao.getAllWatchlist().filter { it.profileId == profileId }
            val favPlaylistItems = localWatchlist.filter { it.mediaId in favoriteIds }.map { w ->
                PlaylistItem(
                    mediaId = w.mediaId,
                    title = w.title,
                    posterUrl = w.posterUrl,
                    backdropUrl = w.backdropUrl,
                    isTvSeries = !w.mediaType.equals("MOVIE", ignoreCase = true),
                    year = w.year
                )
            }
            if (favPlaylistItems.isNotEmpty()) {
                updatedLocalPlaylists.add(
                    Playlist(
                        id = "virtual_favorites",
                        title = "Favorites",
                        items = favPlaylistItems
                    )
                )
            }
        }

        for (localPl in updatedLocalPlaylists) {
            if (!isTraktSessionStillValid(traktGen)) break
            if (localPl.items.isEmpty() || localPl.isSystem || localPl.isDeleted) continue

            var remoteList = remoteLists.find { it.name.equals(localPl.title, ignoreCase = true) }
            if (remoteList == null) {
                remoteList = createTraktUserList(activeToken, localPl.title)
                if (remoteList != null) {
                    remoteLists.add(remoteList)
                }
            }

            val slug = remoteList?.ids?.slug?.takeIf { it.isNotBlank() } ?: continue
            val (tok2, existingRemoteRes) = withTraktTokenRetry(activeToken) { t ->
                traktClient.getListItems(t, slug)
            }
            activeToken = tok2
            val existingRemoteItems = existingRemoteRes.getOrNull() ?: emptyList()
            val remoteTmdbIds = existingRemoteItems.mapNotNull { it.movie?.ids?.tmdb ?: it.show?.ids?.tmdb }.map { it.toString() }.toSet()
            val remoteImdbIds = existingRemoteItems.mapNotNull { it.movie?.ids?.imdb ?: it.show?.ids?.imdb }.toSet()

            val moviesToPush = mutableListOf<TraktMovie>()
            val showsToPush = mutableListOf<TraktShow>()

            for (item in localPl.items) {
                val tmdbId = item.mediaId.toLongOrNull()
                val imdbId = item.mediaId.takeIf { it.startsWith("tt") }
                val alreadyInRemote = (tmdbId != null && remoteTmdbIds.contains(tmdbId.toString())) ||
                    (imdbId != null && remoteImdbIds.contains(imdbId))

                if (!alreadyInRemote) {
                    val ids = TraktIds(tmdb = tmdbId, imdb = imdbId)
                    if (item.isTvSeries) {
                        showsToPush.add(TraktShow(title = item.title, year = item.year, ids = ids))
                    } else {
                        moviesToPush.add(TraktMovie(title = item.title, year = item.year, ids = ids))
                    }
                }
            }

            if ((moviesToPush.isNotEmpty() || showsToPush.isNotEmpty()) && isTraktSessionStillValid(traktGen)) {
                addItemsToTraktUserList(
                    token = activeToken,
                    listSlug = slug,
                    payload = TraktSyncItemsPayload(movies = moviesToPush, shows = showsToPush)
                )
            }
        }
        return SubSyncOutcome(didMutateLocal = mutated, activeToken = activeToken)
    }

    private suspend fun createTraktUserList(token: String, name: String): TraktCustomList? = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JSONObject().apply {
                put("name", name)
                put("description", "Synced from Netvio")
                put("privacy", "private")
                put("display_numbers", false)
                put("allow_comments", false)
            }.toString()

            val req = Request.Builder()
                .url("${TraktClient.BASE_URL}/users/me/lists")
                .header("Content-Type", "application/json")
                .header("trakt-api-version", "2")
                .header("trakt-api-key", TraktClient.DEFAULT_CLIENT_ID)
                .header("Authorization", "Bearer $token")
                .post(bodyJson.toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 201) {
                    val raw = resp.body?.string().orEmpty()
                    json.decodeFromString<TraktCustomList>(raw)
                } else {
                    Log.w(TAG, "Failed to create Trakt list '$name': HTTP ${resp.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error creating Trakt list '$name'", e)
            null
        }
    }

    private suspend fun addItemsToTraktUserList(
        token: String,
        listSlug: String,
        payload: TraktSyncItemsPayload
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${TraktClient.BASE_URL}/users/me/lists/$listSlug/items")
                .header("Content-Type", "application/json")
                .header("trakt-api-version", "2")
                .header("trakt-api-key", TraktClient.DEFAULT_CLIENT_ID)
                .header("Authorization", "Bearer $token")
                .post(json.encodeToString(payload).toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful || resp.code == 201
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error pushing items to Trakt list '$listSlug'", e)
            false
        }
    }

    private fun parseIsoTimestamp(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /* -------------------------------------------------------------------------- */
    /*                         REAL-TIME EVENT DISPATCH HOOKS                     */
    /* -------------------------------------------------------------------------- */

    fun notifyPlaylistsChanged() {
        scope.launch {
            if (!isTrackerSyncAllowed()) return@launch
            try {
                if (syncMutex.isLocked) {
                    enqueuePendingTarget(TrackerSyncTarget.TRAKT)
                    return@launch
                }
                syncMutex.withLock {
                    val traktGen = traktAuthGeneration.get()
                    val traktToken = preferencesRepository.traktAccessToken.first()
                    val traktListsEnabled = preferencesRepository.traktSyncLists.first()
                    if (traktToken.isNotBlank() && traktListsEnabled && isTraktSessionStillValid(traktGen)) {
                        val didMutate = SyncCoordinator.dataMutex.withLock {
                            syncTraktListsInternal(traktToken, currentProfileId(), traktGen).didMutateLocal
                        }
                        if (didMutate) {
                            SupabaseSyncManager.getInstance(context).scheduleSync(1500L)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed async playlist sync", e)
            }
        }
    }

    fun notifyWatchlistAdded(item: MediaItem) {
        scope.launch {
            if (!isTrackerSyncAllowed()) return@launch
            try {
                val isTv = item.type.name.contains("TV", ignoreCase = true) ||
                    item.type.name.contains("ANIME", ignoreCase = true)
                val cleanId = item.id.trim().removePrefix("tmdb:").removePrefix("tmdb/")
                val tmdbId = cleanId.toLongOrNull()
                val imdbId = if (cleanId.startsWith("tt")) cleanId else null

                val traktGen = traktAuthGeneration.get()
                val traktToken = preferencesRepository.traktAccessToken.first()
                val traktEnabled = preferencesRepository.traktSyncWatchlist.first()
                if (traktToken.isNotBlank() && traktEnabled && isTraktSessionStillValid(traktGen)) {
                    val ids = TraktIds(tmdb = tmdbId, imdb = imdbId)
                    val payload = if (isTv) {
                        TraktSyncItemsPayload(shows = listOf(TraktShow(title = item.title, year = item.year, ids = ids)))
                    } else {
                        TraktSyncItemsPayload(movies = listOf(TraktMovie(title = item.title, year = item.year, ids = ids)))
                    }
                    withTraktTokenRetry(traktToken) { t -> traktClient.addToWatchlist(t, payload) }
                }

                val simklToken = preferencesRepository.simklAccessToken.first()
                val simklEnabled = preferencesRepository.simklSyncWatchlist.first()
                if (simklToken.isNotBlank() && simklEnabled) {
                    val syncItem = SimklSyncItem(title = item.title, to = "plantowatch", ids = SimklIds(tmdb = tmdbId, imdb = imdbId))
                    val payload = if (isTv) {
                        SimklSyncPayload(shows = listOf(syncItem))
                    } else {
                        SimklSyncPayload(movies = listOf(syncItem))
                    }
                    simklClient.addToLibrary(simklToken, payload)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed async watchlist push", e)
            }
        }
    }

    fun notifyWatchlistAdded(detail: MediaDetail) {
        scope.launch {
            if (!isTrackerSyncAllowed()) return@launch
            try {
                val isTv = detail.type.name.contains("TV", ignoreCase = true) ||
                    detail.type.name.contains("ANIME", ignoreCase = true)
                val cleanId = detail.id.trim().removePrefix("tmdb:").removePrefix("tmdb/")
                val tmdbId = cleanId.toLongOrNull()
                val imdbId = detail.imdbId ?: if (cleanId.startsWith("tt")) cleanId else null

                val traktGen = traktAuthGeneration.get()
                val traktToken = preferencesRepository.traktAccessToken.first()
                val traktEnabled = preferencesRepository.traktSyncWatchlist.first()
                if (traktToken.isNotBlank() && traktEnabled && isTraktSessionStillValid(traktGen)) {
                    val ids = TraktIds(tmdb = tmdbId, imdb = imdbId)
                    val payload = if (isTv) {
                        TraktSyncItemsPayload(shows = listOf(TraktShow(title = detail.title, year = detail.year, ids = ids)))
                    } else {
                        TraktSyncItemsPayload(movies = listOf(TraktMovie(title = detail.title, year = detail.year, ids = ids)))
                    }
                    withTraktTokenRetry(traktToken) { t -> traktClient.addToWatchlist(t, payload) }
                }

                val simklToken = preferencesRepository.simklAccessToken.first()
                val simklEnabled = preferencesRepository.simklSyncWatchlist.first()
                if (simklToken.isNotBlank() && simklEnabled) {
                    val syncItem = SimklSyncItem(title = detail.title, to = "plantowatch", ids = SimklIds(tmdb = tmdbId, imdb = imdbId))
                    val payload = if (isTv) {
                        SimklSyncPayload(shows = listOf(syncItem))
                    } else {
                        SimklSyncPayload(movies = listOf(syncItem))
                    }
                    simklClient.addToLibrary(simklToken, payload)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed async watchlist detail push", e)
            }
        }
    }

    fun notifyWatchlistRemoved(mediaId: String, title: String? = null, isTv: Boolean = false) {
        notifyWatchlistRemovedBatch(listOf(Triple(mediaId, title, isTv)))
    }

    fun notifyWatchlistRemovedBatch(items: List<Triple<String, String?, Boolean>>) {
        if (items.isEmpty()) return
        scope.launch {
            if (!isTrackerSyncAllowed()) return@launch
            try {
                val traktMovies = mutableListOf<TraktMovie>()
                val traktShows = mutableListOf<TraktShow>()
                val simklMovies = mutableListOf<SimklSyncItem>()
                val simklShows = mutableListOf<SimklSyncItem>()

                for ((mediaId, title, isTv) in items) {
                    val cleanId = mediaId.trim().removePrefix("tmdb:").removePrefix("tmdb/")
                    val tmdbId = cleanId.toLongOrNull()
                    val imdbId = if (cleanId.startsWith("tt")) cleanId else null

                    val traktIds = TraktIds(tmdb = tmdbId, imdb = imdbId)
                    if (isTv) {
                        traktShows.add(TraktShow(title = title ?: "", ids = traktIds))
                    } else {
                        traktMovies.add(TraktMovie(title = title ?: "", ids = traktIds))
                    }

                    val simklItem = SimklSyncItem(title = title, ids = SimklIds(tmdb = tmdbId, imdb = imdbId))
                    if (isTv) simklShows.add(simklItem) else simklMovies.add(simklItem)
                }

                val traktGen = traktAuthGeneration.get()
                val traktToken = preferencesRepository.traktAccessToken.first()
                val traktEnabled = preferencesRepository.traktSyncWatchlist.first()
                if (traktToken.isNotBlank() && traktEnabled && isTraktSessionStillValid(traktGen)) {
                    val payload = TraktSyncItemsPayload(movies = traktMovies, shows = traktShows)
                    withTraktTokenRetry(traktToken) { t -> traktClient.removeFromWatchlist(t, payload) }
                }

                val simklToken = preferencesRepository.simklAccessToken.first()
                val simklEnabled = preferencesRepository.simklSyncWatchlist.first()
                if (simklToken.isNotBlank() && simklEnabled) {
                    simklClient.removeFromLibrary(
                        simklToken,
                        SimklSyncPayload(movies = simklMovies, shows = simklShows)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed async watchlist remove", e)
            }
        }
    }

    fun notifyMediaCompleted(
        mediaId: String,
        title: String,
        isTv: Boolean,
        season: Int? = null,
        episode: Int? = null
    ) {
        scope.launch {
            if (!isTrackerSyncAllowed()) return@launch
            try {
                val cleanId = mediaId.trim().removePrefix("tmdb:").removePrefix("tmdb/")
                val tmdbId = cleanId.toLongOrNull()
                val imdbId = if (cleanId.startsWith("tt")) cleanId else null

                val traktGen = traktAuthGeneration.get()
                val traktToken = preferencesRepository.traktAccessToken.first()
                val traktEnabled = preferencesRepository.traktSyncHistory.first()
                if (traktToken.isNotBlank() && traktEnabled && isTraktSessionStillValid(traktGen)) {
                    val ids = TraktIds(tmdb = tmdbId, imdb = imdbId)
                    val payload = if (isTv) {
                        val seasons = if (season != null && episode != null) {
                            listOf(TraktSyncSeason(number = season, episodes = listOf(TraktSyncEpisode(number = episode))))
                        } else null
                        TraktHistoryPayload(shows = listOf(TraktHistoryShow(title = title, ids = ids, seasons = seasons)))
                    } else {
                        TraktHistoryPayload(movies = listOf(TraktMovie(title = title, ids = ids)))
                    }
                    withTraktTokenRetry(traktToken) { t -> traktClient.addHistoryItems(t, payload) }
                }

                val simklToken = preferencesRepository.simklAccessToken.first()
                val simklEnabled = preferencesRepository.simklSyncHistory.first()
                if (simklToken.isNotBlank() && simklEnabled) {
                    val ids = SimklIds(tmdb = tmdbId, imdb = imdbId)
                    if (isTv && season != null && episode != null) {
                        val historyPayload = SimklHistoryPayload(
                            shows = listOf(
                                SimklHistoryShow(
                                    title = title,
                                    ids = ids,
                                    seasons = listOf(
                                        SimklHistorySeason(
                                            number = season,
                                            episodes = listOf(SimklHistoryEpisode(number = episode))
                                        )
                                    )
                                )
                            )
                        )
                        simklClient.addHistoryItems(simklToken, historyPayload)
                        simklClient.addToLibrary(
                            simklToken,
                            SimklSyncPayload(shows = listOf(SimklSyncItem(title = title, to = "watching", ids = ids)))
                        )
                    } else {
                        val syncItem = SimklSyncItem(title = title, to = "completed", ids = ids)
                        val listPayload = if (isTv) {
                            SimklSyncPayload(shows = listOf(syncItem))
                        } else {
                            SimklSyncPayload(movies = listOf(syncItem))
                        }
                        simklClient.addToLibrary(simklToken, listPayload)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed async history push", e)
            }
        }
    }

    companion object {
        private const val TAG = "MediaSyncManager"

        @Volatile
        private var instance: MediaSyncManager? = null

        fun getInstance(context: Context): MediaSyncManager {
            return instance ?: synchronized(this) {
                instance ?: MediaSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
