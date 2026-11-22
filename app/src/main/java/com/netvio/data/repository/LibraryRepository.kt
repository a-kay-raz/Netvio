package com.netvio.data.repository

import android.content.Context
import com.netvio.core.model.EpisodeItem
import com.netvio.core.model.MediaDetail
import com.netvio.core.model.MediaItem
import com.netvio.data.local.AppDatabase
import com.netvio.data.local.PlaybackHistoryDao
import com.netvio.data.local.PlaybackHistoryEntity
import com.netvio.data.local.WatchlistDao
import com.netvio.data.local.WatchlistEntity
import com.netvio.data.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(
    private val watchlistDao: WatchlistDao,
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val profileManager: ProfileManager? = null,
    private val syncManagerProvider: (() -> com.netvio.data.sync.MediaSyncManager)? = null,
    private val supabaseSyncManagerProvider: (() -> com.netvio.data.sync.supabase.SupabaseSyncManager)? = null
) {
    private fun notifyCloudSync() {
        supabaseSyncManagerProvider?.invoke()?.scheduleSync()
    }
    private fun currentProfileId(): String = profileManager?.activeProfile?.value?.id ?: "default_user"

    private suspend fun nextWatchlistTs(mediaId: String, profileId: String, now: Long): Long {
        val raw = watchlistDao.getRawItem(mediaId, profileId)
        return maxOf(now, (raw?.clientUpdatedAtMs ?: 0L) + 1L)
    }

    private suspend fun nextPlaybackTs(id: String, now: Long): Long {
        val raw = playbackHistoryDao.getRawById(id)
        return maxOf(now, maxOf(raw?.clientUpdatedAtMs ?: 0L, raw?.lastWatchedTimestamp ?: 0L) + 1L)
    }

    val watchlist: Flow<List<WatchlistEntity>> =
        profileManager?.activeProfile?.flatMapLatest { profile ->
            watchlistDao.observeWatchlist(profile.id)
        } ?: watchlistDao.observeWatchlist("default_user")

    val continueWatching: Flow<List<PlaybackHistoryEntity>> =
        profileManager?.activeProfile?.flatMapLatest { profile ->
            playbackHistoryDao.observeAllContinueWatching(profile.id)
        } ?: playbackHistoryDao.observeAllContinueWatching("default_user")

    val history: Flow<List<PlaybackHistoryEntity>> =
        profileManager?.activeProfile?.flatMapLatest { profile ->
            playbackHistoryDao.observeHistory(profileId = profile.id)
        } ?: playbackHistoryDao.observeHistory(profileId = "default_user")

    val completed: Flow<List<PlaybackHistoryEntity>> =
        profileManager?.activeProfile?.flatMapLatest { profile ->
            playbackHistoryDao.observeCompleted(profileId = profile.id)
        } ?: playbackHistoryDao.observeCompleted(profileId = "default_user")

    fun getContinueWatching(provider: String? = null): Flow<List<PlaybackHistoryEntity>> {
        return profileManager?.activeProfile?.flatMapLatest { profile ->
            playbackHistoryDao.observeContinueWatching(provider, profile.id)
        } ?: playbackHistoryDao.observeContinueWatching(provider, "default_user")
    }

    fun isWatchlisted(mediaId: String): Flow<Boolean> {
        return profileManager?.activeProfile?.flatMapLatest { profile ->
            watchlistDao.isWatchlisted(mediaId, profile.id)
        } ?: watchlistDao.isWatchlisted(mediaId, "default_user")
    }

    fun getWatchlistEntity(mediaId: String): Flow<WatchlistEntity?> {
        return profileManager?.activeProfile?.flatMapLatest { profile ->
            watchlistDao.observeItem(mediaId, profile.id)
        } ?: watchlistDao.observeItem(mediaId, "default_user")
    }

    fun getLatestPlaybackForMedia(mediaId: String, provider: String? = null): Flow<PlaybackHistoryEntity?> {
        return profileManager?.activeProfile?.flatMapLatest { profile ->
            playbackHistoryDao.observeLatestPlaybackForMedia(mediaId, provider, profile.id)
        } ?: playbackHistoryDao.observeLatestPlaybackForMedia(mediaId, provider, "default_user")
    }

    fun getPlaybackForMedia(mediaId: String, provider: String? = null): Flow<List<PlaybackHistoryEntity>> {
        return profileManager?.activeProfile?.flatMapLatest { profile ->
            playbackHistoryDao.observePlaybackForMedia(mediaId, provider, profile.id)
        } ?: playbackHistoryDao.observePlaybackForMedia(mediaId, provider, "default_user")
    }

    suspend fun setWatchlistStatus(mediaDetail: MediaDetail, status: String) {
        val profileId = currentProfileId()
        val now = System.currentTimeMillis()
        val existing = watchlistDao.getWatchlistItem(mediaDetail.id, profileId)
        if (existing != null) {
            watchlistDao.updateStatus(mediaDetail.id, status, profileId, now)
        } else {
            val ts = nextWatchlistTs(mediaDetail.id, profileId, now)
            val entity = WatchlistEntity(
                mediaId = mediaDetail.id,
                profileId = profileId,
                title = mediaDetail.title,
                posterUrl = mediaDetail.posterUrl,
                backdropUrl = mediaDetail.backdropUrl,
                mediaType = mediaDetail.type.name,
                rating = mediaDetail.rating?.toDoubleOrNull(),
                year = mediaDetail.year,
                status = status,
                addedTimestamp = now,
                isDirty = true,
                isDeleted = false,
                clientUpdatedAtMs = ts
            )
            watchlistDao.insert(entity)
        }
        if (status == "TO_BE_WATCHED" || status == "WATCHING") {
            syncManagerProvider?.invoke()?.notifyWatchlistAdded(mediaDetail)
        } else if (status == "COMPLETED") {
            val isTv = mediaDetail.type.name.contains("TV", ignoreCase = true) || mediaDetail.type.name.contains("ANIME", ignoreCase = true)
            syncManagerProvider?.invoke()?.notifyMediaCompleted(mediaDetail.id, mediaDetail.title, isTv)
        }
        notifyCloudSync()
    }

    suspend fun setWatchlistStatus(item: MediaItem, status: String) {
        val profileId = currentProfileId()
        val now = System.currentTimeMillis()
        val existing = watchlistDao.getWatchlistItem(item.id, profileId)
        if (existing != null) {
            watchlistDao.updateStatus(item.id, status, profileId, now)
        } else {
            val ts = nextWatchlistTs(item.id, profileId, now)
            val entity = WatchlistEntity(
                mediaId = item.id,
                profileId = profileId,
                title = item.title,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                mediaType = item.type.name,
                rating = item.rating?.toDoubleOrNull(),
                year = item.year,
                status = status,
                addedTimestamp = now,
                isDirty = true,
                isDeleted = false,
                clientUpdatedAtMs = ts
            )
            watchlistDao.insert(entity)
        }
        if (status == "TO_BE_WATCHED" || status == "WATCHING") {
            syncManagerProvider?.invoke()?.notifyWatchlistAdded(item)
        } else if (status == "COMPLETED") {
            val isTv = item.type.name.contains("TV", ignoreCase = true) || item.type.name.contains("ANIME", ignoreCase = true)
            syncManagerProvider?.invoke()?.notifyMediaCompleted(item.id, item.title, isTv)
        }
        notifyCloudSync()
    }

    suspend fun toggleWatchlist(item: MediaItem): Boolean {
        val profileId = currentProfileId()
        val exists = watchlistDao.countWatchlist(item.id, profileId) > 0
        val now = System.currentTimeMillis()
        return if (exists) {
            watchlistDao.softDelete(item.id, profileId, now)
            val isTv = item.type.name.contains("TV", ignoreCase = true) || item.type.name.contains("ANIME", ignoreCase = true)
            syncManagerProvider?.invoke()?.notifyWatchlistRemoved(item.id, item.title, isTv)
            notifyCloudSync()
            false
        } else {
            val ts = nextWatchlistTs(item.id, profileId, now)
            val entity = WatchlistEntity(
                mediaId = item.id,
                profileId = profileId,
                title = item.title,
                posterUrl = item.posterUrl,
                backdropUrl = item.backdropUrl,
                mediaType = item.type.name,
                rating = item.rating?.toDoubleOrNull(),
                year = item.year,
                status = "TO_BE_WATCHED",
                addedTimestamp = now,
                isDirty = true,
                isDeleted = false,
                clientUpdatedAtMs = ts
            )
            watchlistDao.insert(entity)
            syncManagerProvider?.invoke()?.notifyWatchlistAdded(item)
            notifyCloudSync()
            true
        }
    }

    suspend fun toggleWatchlist(mediaDetail: MediaDetail): Boolean {
        val profileId = currentProfileId()
        val exists = watchlistDao.countWatchlist(mediaDetail.id, profileId) > 0
        val now = System.currentTimeMillis()
        return if (exists) {
            watchlistDao.softDelete(mediaDetail.id, profileId, now)
            val isTv = mediaDetail.type.name.contains("TV", ignoreCase = true) || mediaDetail.type.name.contains("ANIME", ignoreCase = true)
            syncManagerProvider?.invoke()?.notifyWatchlistRemoved(mediaDetail.id, mediaDetail.title, isTv)
            notifyCloudSync()
            false
        } else {
            val ts = nextWatchlistTs(mediaDetail.id, profileId, now)
            val entity = WatchlistEntity(
                mediaId = mediaDetail.id,
                profileId = profileId,
                title = mediaDetail.title,
                posterUrl = mediaDetail.posterUrl,
                backdropUrl = mediaDetail.backdropUrl,
                mediaType = mediaDetail.type.name,
                rating = mediaDetail.rating?.toDoubleOrNull(),
                year = mediaDetail.year,
                status = "TO_BE_WATCHED",
                addedTimestamp = now,
                isDirty = true,
                isDeleted = false,
                clientUpdatedAtMs = ts
            )
            watchlistDao.insert(entity)
            syncManagerProvider?.invoke()?.notifyWatchlistAdded(mediaDetail)
            notifyCloudSync()
            true
        }
    }

    suspend fun removeFromWatchlist(mediaId: String) {
        val profileId = currentProfileId()
        val now = System.currentTimeMillis()
        val existing = watchlistDao.getWatchlistItem(mediaId, profileId)
        watchlistDao.softDelete(mediaId, profileId, now)
        syncManagerProvider?.invoke()?.notifyWatchlistRemoved(
            mediaId = mediaId,
            title = existing?.title,
            isTv = com.netvio.data.sync.TrackerReconciliationPolicy.isTvMediaType(existing?.mediaType)
        )
        notifyCloudSync()
    }

    suspend fun removeFromWatchlistBatch(mediaIds: List<String>) {
        if (mediaIds.isNotEmpty()) {
            val profileId = currentProfileId()
            val now = System.currentTimeMillis()
            val targetSet = mediaIds.toSet()
            val removedMeta = watchlistDao.getAllWatchlist()
                .filter { it.profileId == profileId && it.mediaId in targetSet }
                .map {
                    Triple(
                        it.mediaId,
                        it.title,
                        com.netvio.data.sync.TrackerReconciliationPolicy.isTvMediaType(it.mediaType)
                    )
                }
                .ifEmpty { mediaIds.map { Triple(it, null, false) } }
            watchlistDao.softDeleteBatch(mediaIds, profileId, now)
            syncManagerProvider?.invoke()?.notifyWatchlistRemovedBatch(removedMeta)
            notifyCloudSync()
        }
    }

    suspend fun insertWatchlistBatch(items: List<WatchlistEntity>) {
        val profileId = currentProfileId()
        val now = System.currentTimeMillis()
        for (item in items) {
            val ts = nextWatchlistTs(item.mediaId, profileId, now)
            watchlistDao.insert(item.copy(profileId = profileId, isDeleted = false, isDirty = true, clientUpdatedAtMs = ts))
        }
        notifyCloudSync()
    }

    suspend fun insertPlaybackHistoryBatch(items: List<PlaybackHistoryEntity>) {
        if (items.isEmpty()) return
        val profileId = currentProfileId()
        val now = System.currentTimeMillis()
        val scoped = items.map { entity ->
            val properId = if (entity.id.contains("::")) {
                entity.id
            } else {
                PlaybackHistoryEntity.buildId(entity.provider, entity.mediaId, entity.seasonNumber, entity.episodeNumber, profileId)
            }
            val ts = nextPlaybackTs(properId, now)
            entity.copy(
                id = properId,
                profileId = profileId,
                isDeleted = false,
                isDirty = true,
                lastWatchedTimestamp = ts,
                clientUpdatedAtMs = ts
            )
        }
        playbackHistoryDao.upsertBatch(scoped)
        notifyCloudSync()
    }

    suspend fun addToWatchlist(
        mediaId: String,
        title: String,
        posterUrl: String?,
        backdropUrl: String?,
        mediaType: String,
        rating: Double?,
        year: Int?,
        status: String = "TO_BE_WATCHED"
    ) {
        val profileId = currentProfileId()
        val now = System.currentTimeMillis()
        val ts = nextWatchlistTs(mediaId, profileId, now)
        val entity = WatchlistEntity(
            mediaId = mediaId,
            profileId = profileId,
            title = title,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            mediaType = mediaType,
            rating = rating,
            year = year,
            status = status,
            addedTimestamp = now,
            isDirty = true,
            isDeleted = false,
            clientUpdatedAtMs = ts
        )
        watchlistDao.insert(entity)
        notifyCloudSync()
    }

    suspend fun updatePlaybackProgress(
        mediaId: String,
        title: String,
        episodeItem: EpisodeItem? = null,
        currentPositionMs: Long,
        durationMs: Long,
        backdropUrl: String?,
        posterUrl: String?,
        provider: String? = null,
        mediaType: String? = null,
        episodeData: String? = null,
        notifyCloud: Boolean = true
    ) {
        val season = episodeItem?.seasonNumber
        val episode = episodeItem?.episodeNumber
        val epTitle = episodeItem?.title

        updatePlaybackProgressInternal(
            mediaId = mediaId,
            title = title,
            episodeTitle = epTitle,
            seasonNumber = season,
            episodeNumber = episode,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            backdropUrl = backdropUrl,
            posterUrl = posterUrl,
            provider = provider,
            mediaType = mediaType,
            episodeData = episodeData,
            notifyCloud = notifyCloud
        )
    }

    suspend fun updatePlaybackProgress(
        mediaId: String,
        title: String,
        episodeTitle: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        currentPositionMs: Long,
        durationMs: Long,
        backdropUrl: String?,
        posterUrl: String?,
        provider: String? = null,
        mediaType: String? = null,
        episodeData: String? = null,
        notifyCloud: Boolean = true
    ) {
        updatePlaybackProgressInternal(
            mediaId = mediaId,
            title = title,
            episodeTitle = episodeTitle,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            backdropUrl = backdropUrl,
            posterUrl = posterUrl,
            provider = provider,
            mediaType = mediaType,
            episodeData = episodeData,
            notifyCloud = notifyCloud
        )
    }

    private suspend fun updatePlaybackProgressInternal(
        mediaId: String,
        title: String,
        episodeTitle: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        currentPositionMs: Long,
        durationMs: Long,
        backdropUrl: String?,
        posterUrl: String?,
        provider: String? = null,
        mediaType: String? = null,
        episodeData: String? = null,
        notifyCloud: Boolean = true
    ) {
        val profileId = currentProfileId()
        val id = PlaybackHistoryEntity.buildId(provider, mediaId, seasonNumber, episodeNumber, profileId)

        val isCompleted = if (durationMs > 0) {
            (currentPositionMs.toFloat() / durationMs.toFloat()) >= 0.92f
        } else {
            false
        }

        val existing = playbackHistoryDao.getProgress(id)
        val watchlistItem = if (posterUrl.isNullOrBlank() || backdropUrl.isNullOrBlank()) {
            watchlistDao.getWatchlistItem(mediaId, profileId)
        } else null
        val isTv = seasonNumber != null || episodeNumber != null ||
            mediaType?.contains("TV", ignoreCase = true) == true ||
            mediaType?.contains("ANIME", ignoreCase = true) == true
        val cachedTmdb = if (posterUrl.isNullOrBlank() || backdropUrl.isNullOrBlank()) {
            com.netvio.core.util.TmdbBridge.getCachedEnrichedDetails(mediaId, isTv)
        } else null

        val resolvedPoster = posterUrl?.takeIf { it.isNotBlank() }
            ?: existing?.posterUrl?.takeIf { it.isNotBlank() }
            ?: watchlistItem?.posterUrl?.takeIf { it.isNotBlank() }
            ?: cachedTmdb?.posterUrl?.takeIf { it.isNotBlank() }

        val resolvedBackdrop = backdropUrl?.takeIf { it.isNotBlank() }
            ?: existing?.backdropUrl?.takeIf { it.isNotBlank() }
            ?: watchlistItem?.backdropUrl?.takeIf { it.isNotBlank() }
            ?: cachedTmdb?.backdropUrl?.takeIf { it.isNotBlank() }
            ?: resolvedPoster

        val ts = nextPlaybackTs(id, System.currentTimeMillis())
        val entity = PlaybackHistoryEntity(
            id = id,
            mediaId = mediaId,
            title = title,
            episodeTitle = episodeTitle,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            posterUrl = resolvedPoster,
            backdropUrl = resolvedBackdrop,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            lastWatchedTimestamp = ts,
            isCompleted = isCompleted,
            provider = provider,
            mediaType = mediaType,
            episodeData = episodeData,
            isDismissedFromContinueWatching = false,
            profileId = profileId,
            isDirty = true,
            isDeleted = false,
            clientUpdatedAtMs = ts
        )

        playbackHistoryDao.upsert(entity)
        if (notifyCloud) {
            notifyCloudSync()
        }

        // Synchronize Watchlist status if this media is bookmarked (never auto-complete whole TV series on one episode)
        if (watchlistDao.countWatchlist(mediaId, profileId) > 0) {
            val watchlistStatus = if (isCompleted && !isTv) "COMPLETED" else "WATCHING"
            watchlistDao.updateStatus(mediaId, watchlistStatus, profileId)
        }
    }

    suspend fun updateWatchlistStatus(mediaId: String, status: String) {
        val now = System.currentTimeMillis()
        watchlistDao.updateStatus(mediaId, status, currentProfileId(), now)
        notifyCloudSync()
    }

    suspend fun getPlaybackProgress(
        id: String,
        provider: String? = null,
        mediaId: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): PlaybackHistoryEntity? {
        val direct = playbackHistoryDao.getProgress(id)
        if (direct != null) return direct

        val profileId = currentProfileId()
        if (mediaId != null) {
            val scopedId = PlaybackHistoryEntity.buildId(provider, mediaId, seasonNumber, episodeNumber, profileId)
            val scoped = playbackHistoryDao.getProgress(scopedId)
            if (scoped != null) return scoped

            val fallbackAnyProvider = playbackHistoryDao.getProgressForMediaAnyProvider(
                mediaId = mediaId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                profileId = profileId
            )
            if (fallbackAnyProvider != null) return fallbackAnyProvider
        }
        return null
    }

    suspend fun dismissFromContinueWatching(id: String) {
        val now = System.currentTimeMillis()
        playbackHistoryDao.dismissFromContinueWatching(id, now)
        notifyCloudSync()
    }

    suspend fun dismissBatchFromContinueWatching(ids: List<String>) {
        if (ids.isNotEmpty()) {
            val now = System.currentTimeMillis()
            playbackHistoryDao.dismissBatchFromContinueWatching(ids, now)
            notifyCloudSync()
        }
    }

    suspend fun clearContinueWatching(provider: String? = null) {
        val now = System.currentTimeMillis()
        playbackHistoryDao.clearContinueWatching(provider, currentProfileId(), now)
        notifyCloudSync()
    }

    suspend fun removeFromHistory(id: String) {
        val now = System.currentTimeMillis()
        playbackHistoryDao.softDelete(id, now)
        notifyCloudSync()
    }

    suspend fun removeFromHistoryBatch(ids: List<String>) {
        if (ids.isNotEmpty()) {
            val now = System.currentTimeMillis()
            playbackHistoryDao.softDeleteBatch(ids, now)
            notifyCloudSync()
        }
    }

    suspend fun removeFromHistoryByMediaIdsBatch(mediaIds: List<String>) {
        if (mediaIds.isNotEmpty()) {
            val now = System.currentTimeMillis()
            playbackHistoryDao.softDeleteBatchByMediaId(mediaIds, currentProfileId(), now)
            notifyCloudSync()
        }
    }

    suspend fun markAsCompleted(id: String) {
        val profileId = currentProfileId()
        val now = System.currentTimeMillis()
        val existing = playbackHistoryDao.getProgress(id)
        if (existing != null) {
            val ts = nextPlaybackTs(id, now)
            val updated = existing.copy(
                isCompleted = true,
                currentPositionMs = existing.durationMs.coerceAtLeast(existing.currentPositionMs),
                lastWatchedTimestamp = ts,
                isDeleted = false,
                isDirty = true,
                clientUpdatedAtMs = ts
            )
            playbackHistoryDao.upsert(updated)
            if (watchlistDao.countWatchlist(existing.mediaId, profileId) > 0) {
                watchlistDao.updateStatus(existing.mediaId, "COMPLETED", profileId, now)
            }
            syncManagerProvider?.invoke()?.notifyMediaCompleted(existing.mediaId, existing.title, existing.seasonNumber != null, existing.seasonNumber, existing.episodeNumber)
            notifyCloudSync()
        } else {
            // Check if it's a mediaId in watchlist directly
            if (watchlistDao.countWatchlist(id, profileId) > 0) {
                watchlistDao.updateStatus(id, "COMPLETED", profileId, now)
            }
            syncManagerProvider?.invoke()?.notifyMediaCompleted(id, "", false)
            notifyCloudSync()
        }
    }

    suspend fun clearHistory() {
        val now = System.currentTimeMillis()
        playbackHistoryDao.softClearHistory(currentProfileId(), now)
        notifyCloudSync()
    }

    companion object {
        @Volatile
        private var instance: LibraryRepository? = null

        fun getInstance(context: Context): LibraryRepository {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val db = AppDatabase.getInstance(context)
                    val profileMgr = ProfileManager.getInstance(context)
                    val appContext = context.applicationContext
                    LibraryRepository(
                        db.watchlistDao(),
                        db.playbackHistoryDao(),
                        profileMgr,
                        syncManagerProvider = { com.netvio.data.sync.MediaSyncManager.getInstance(appContext) },
                        supabaseSyncManagerProvider = { com.netvio.data.sync.supabase.SupabaseSyncManager.getInstance(appContext) }
                    ).also { instance = it }
                }
            }
        }
    }
}
